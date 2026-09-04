import express, { Request, Response, NextFunction } from 'express';
import cors from 'cors';
import multer from 'multer';
import { exec } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import QRCode from 'qrcode';
import { config, paths, ensureDirs, loadSettings, updateSettings, getEffectiveDownloadDir } from './config';
import { getLanIp, getConnectUrl } from './network';
import {
  registerDevice,
  getDevice,
  authenticate,
  listDevices,
  deviceIcon,
  listReceived,
  listActivity,
  recordReceived,
  removeReceived,
  clearReceived,
  clearAllHistory,
  finalizeOutgoing,
  getTransfer,
  completeTransfer,
  HttpError,
  shareCode,
} from './services/services';
import { DeviceType } from './types';
import { APP_VERSION } from './version';

ensureDirs();

const lanIp = config.discoverLanIp ? getLanIp() : null;
const connectUrl = getConnectUrl(config.port, lanIp);

/**
 * 修复 Content-Disposition 中文文件名 mojibake：
 * Android OkHttp MultipartBody 可能将 UTF-8 中文当作 Latin-1 发送，
 * multer 收到的 originalname 是 UTF-8 字节被误读为 Latin-1 的结果。
 */
function decodeFilename(raw: string): string {
  try {
    const buf = Buffer.from(raw, 'latin1');
    const utf8 = buf.toString('utf8');
    // 诊断：对比 latin1→utf8 前后
    // 若还原后不含替换字符（U+FFFD），采用还原结果
    if (utf8.indexOf('\ufffd') === -1) return utf8;
    return raw;
  } catch {
    return raw;
  }
}

function sanitizeName(name: string): string {
  const decoded = decodeFilename(name);
  const base = path.basename(decoded).replace(/[\/\\:*?"<>|]/g, '_');
  // 允许中文、CJK 符号/标点、全角字符、括号
  return base.replace(/[^\w.\- \u4e00-\u9fff\u3000-\u303f\uff00-\uffef()（）\[\]【】]/g, '_') || 'file';
}

const uploadStorage = multer.diskStorage({
  destination: (_req, _file, cb) => {
    const baseDir = getEffectiveDownloadDir();
    const dir = path.join(baseDir, 'local-sharing-files');
    fs.mkdirSync(dir, { recursive: true });
    cb(null, dir);
  },
  filename: (_req, file, cb) => {
    const safe = sanitizeName(file.originalname);
    // 同名文件自动加序号，避免多设备/多次传输覆盖
    let final = safe;
    let n = 1;
    const ext = path.extname(safe);
    const stem = path.basename(safe, ext);
    const dir = path.join(getEffectiveDownloadDir(), 'local-sharing-files');
    while (fs.existsSync(path.join(dir, final))) {
      final = `${stem} (${n})${ext}`;
      n++;
    }
    cb(null, final);
  },
});

const transferStorage = multer.diskStorage({
  destination: (req: any, _file, cb) => {
    const dir = path.join(paths.outbox, req.tmpId);
    fs.mkdirSync(dir, { recursive: true });
    cb(null, dir);
  },
  filename: (_req, file, cb) => cb(null, sanitizeName(file.originalname)),
});

const upload = multer({ storage: uploadStorage, limits: { fileSize: config.maxFileSize } });
const transferUpload = multer({ storage: transferStorage, limits: { fileSize: config.maxFileSize } });

export function buildApp(): express.Express {
  const app = express();
  app.use(cors());
  app.use(express.json());
  // 手机端 ShareApi 使用 okhttp FormBody（application/x-www-form-urlencoded）提交注册，
  // 必须显式启用 urlencoded 解析，否则 req.body 为 undefined，name/type/clientId 均取不到。
  app.use(express.urlencoded({ extended: false }));

  // ---- 信任边界防护 ----
  /**
   * Host/Origin 白名单：仅允许 IP 字面量、localhost 与本机主机名。
   * 阻断 DNS rebinding（恶意域名解析到内网地址后 Host 为攻击域名）与跨站请求（Origin 为攻击站点）。
   */
  const isTrustedHost = (hostHeader: string | undefined): boolean => {
    if (!hostHeader) return false;
    let host = hostHeader.split(':')[0].trim();
    if (host.startsWith('[') && host.endsWith(']')) host = host.slice(1, -1);
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) return true; // IPv4 字面量
    if (host.includes(':')) return true; // IPv6 字面量
    const lower = host.toLowerCase();
    return lower === 'localhost' || lower === os.hostname().toLowerCase();
  };
  app.use((req, res, next) => {
    if (!isTrustedHost(req.headers.host)) {
      return res.status(403).json({ error: 'invalid_host' });
    }
    const origin = req.headers.origin as string | undefined;
    if (origin) {
      try {
        if (!isTrustedHost(new URL(origin).host)) {
          return res.status(403).json({ error: 'invalid_origin' });
        }
      } catch {
        return res.status(403).json({ error: 'invalid_origin' });
      }
    }
    next();
  });

  /**
   * 仪表盘专用 API 仅限本机回环访问。
   * 手机只调用 info/register/upload/transfer 系列；列表、删除、设置、打开目录等
   * 管理面接口不允许局域网其他设备触达。
   */
  const localOnly = (req: Request, res: Response, next: NextFunction) => {
    const addr = req.socket.remoteAddress || '';
    if (addr === '127.0.0.1' || addr === '::1' || addr === '::ffff:127.0.0.1') return next();
    res.status(403).json({ error: 'local_only' });
  };

  // 为 transfer/out 生成临时目录 id
  app.use('/api/transfer/out', (req: any, _res, next) => {
    req.tmpId = `up_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
    next();
  });

  // ---- 健康检查 ----
  app.get('/health', (_req, res) => res.json({ status: 'ok' }));

  // ---- 连接信息（供手机扫码后获取） ----
  app.get('/api/info', (_req, res) => {
    res.json({
      name: config.deviceName,
      version: APP_VERSION,
      port: config.port,
      lanIp,
      connectUrl,
      requiresCode: shareCode.length > 0,
      wsPath: '/ws',
    });
  });

  // ---- 二维码图片（供电脑端仪表盘展示） ----
  app.get('/api/qr', async (_req, res) => {
    try {
      const buf = await QRCode.toBuffer(connectUrl, { type: 'png', margin: 1, width: 320 });
      res.set('Content-Type', 'image/png');
      res.send(buf);
    } catch (e) {
      res.status(500).json({ error: 'qr_failed' });
    }
  });

  // ---- 设备注册（手机/平板） ----
  app.post('/api/devices', (req, res) => {
    if (shareCode && req.body?.code !== shareCode) {
      return res.status(403).json({ error: 'invalid_code' });
    }
    const name = String(req.body?.name || 'Device');
    const type = (req.body?.type as DeviceType) || 'unknown';
    const clientId =
      typeof req.body?.clientId === 'string' && req.body.clientId.length > 0
        ? req.body.clientId
        : undefined;
    const device = registerDevice(name, type, clientId);
    res.json({
      deviceId: device.id,
      token: device.token,
      wsUrl: `${connectUrl.replace('http', 'ws')}/ws?token=${device.token}`,
    });
  });

  // ---- 设备列表（电脑端仪表盘） ----
  app.get('/api/devices', localOnly, (_req, res) => {
    res.json({
      devices: listDevices().map((d) => ({
        id: d.id,
        name: d.name,
        type: d.type,
        online: !!d.ws,
        icon: deviceIcon(d.type),
      })),
    });
  });

  // ---- 接收文件列表 ----
  app.get('/api/received', localOnly, (_req, res) => {
    res.json({ files: listReceived() });
  });

  // ---- 统一活动流（收到的文件 + 发送历史） ----
  app.get('/api/activity', localOnly, (_req, res) => {
    res.json({ activities: listActivity() });
  });

  // ---- 删除单条"收到的文件" ----
  app.delete('/api/received/:id', localOnly, (req, res) => {
    const ok = removeReceived(req.params.id);
    if (!ok) return res.status(404).json({ error: 'not_found' });
    res.json({ ok: true });
  });

  // ---- 清空"收到的文件" ----
  app.delete('/api/received', localOnly, (_req, res) => {
    clearReceived();
    res.json({ ok: true });
  });

  // ---- 手机 → 电脑：上传 ----
  app.post('/api/upload', (req: any, res, next) => {
    // 先鉴权再落盘：无效 token 直接 401，不在磁盘留下孤儿文件
    const device = authenticate(req.headers['x-token'] as string);
    if (!device) return res.status(401).json({ error: 'unauthorized' });
    upload.any()(req, res, (err: any) => {
      if (err) return next(new HttpError(400, err.message));
      try {
        const files = (req.files || []).map((f: any) => ({
          name: decodeFilename(f.originalname),
          savedPath: f.path,
          size: f.size,
        }));
        const asFolder = req.body?.asFolder === '1' || req.body?.asFolder === 'true';
        const saved = recordReceived(device, files, asFolder);
        res.json({ ok: true, files: saved });
      } catch (e) {
        next(e);
      }
    });
  });

  // ---- 电脑 → 手机：推送 ----
  app.post('/api/transfer/out', (req: any, res, next) => {
    transferUpload.any()(req, res, (err: any) => {
      if (err) return next(new HttpError(400, err.message));
      (async () => {
        try {
          const deviceId = req.body?.deviceId;
          if (!deviceId || !getDevice(deviceId)) {
            return res.status(404).json({ error: 'device_not_found' });
          }
          const files = (req.files || []).map((f: any) => ({
            name: decodeFilename(f.originalname),
            savedPath: f.path,
            size: f.size,
          }));
          const asFolder = req.body?.asFolder === '1' || req.body?.asFolder === 'true';
          const transfer = await finalizeOutgoing(deviceId, files, asFolder);
          res.json({ ok: true, transfer });
        } catch (e) {
          next(e);
        }
      })();
    });
  });

  // ---- 手机拉取传输文件 ----
  app.get('/api/transfer/:id', (req, res, next) => {
    try {
      // 能力地址 id 本身不可枚举，但仍要求持有设备 token（传输内容凭据双保险）
      const device = authenticate(req.headers['x-token'] as string);
      if (!device) return res.status(401).json({ error: 'unauthorized' });
      const t = getTransfer(req.params.id);
      if (!t) return res.status(404).json({ error: 'not_found' });
      res.download(t.filePath, t.name, (err) => {
        if (err) console.error('download error', err);
      });
    } catch (e) {
      next(e);
    }
  });

  // ---- 手机确认接收完成 ----
  app.post('/api/transfer/:id/ack', (req, res) => {
    completeTransfer(req.params.id);
    res.json({ ok: true });
  });

  // ---- 用户设置 ----
  app.get('/api/settings', localOnly, (_req, res) => {
    res.json(loadSettings());
  });

  app.post('/api/settings', localOnly, (req, res) => {
    const { downloadDir, lastDeviceId } = req.body;
    if (downloadDir && !fs.existsSync(downloadDir)) {
      return res.status(400).json({ error: '目录不存在' });
    }
    const s = updateSettings({
      downloadDir: downloadDir !== undefined ? downloadDir : undefined,
      lastDeviceId: lastDeviceId !== undefined ? lastDeviceId : undefined,
    });
    res.json({ ok: true, settings: s });
  });

  // ---- 清空全部历史（仅清记录，不删磁盘文件） ----
  app.delete('/api/history', localOnly, (_req, res) => {
    clearAllHistory();
    res.json({ ok: true });
  });

  // ---- 在系统资源管理器中打开文件所在位置 ----
  app.post('/api/open-folder', localOnly, (req, res) => {
    const filePath: string | undefined = req.body?.path;
    if (!filePath || !fs.existsSync(filePath)) {
      return res.status(404).json({ error: '文件不存在' });
    }
    const cmd = process.platform === 'win32'
      ? `explorer.exe /select,"${filePath}"`
      : process.platform === 'darwin'
        ? `open -R "${filePath}"`
        : `xdg-open "${path.dirname(filePath)}"`;
    exec(cmd, (err) => {
      if (err) return res.status(500).json({ error: err.message });
      res.json({ ok: true });
    });
  });

  // ---- 弹出系统文件夹选择对话框，返回所选绝对路径 ----
  // 用 PowerShell 的 Shell.Application.BrowseForFolder（BIF_USENEWUI 现代风格），
  // 避免改动 Rust/重编。仅 Windows 支持。
  app.post('/api/pick-folder', localOnly, (_req, res) => {
    if (process.platform !== 'win32') {
      return res.status(400).json({ error: 'unsupported_platform' });
    }
    const ps = [
      '$shell = New-Object -ComObject Shell.Application',
      "$folder = $shell.BrowseForFolder(0, '选择文件保存目录', 0x1000, 0x11)",
      'if ($folder -ne $null) { $folder.Self.Path }',
    ].join('\n');
    // 用 -EncodedCommand（UTF-16LE base64）规避引号转义问题
    const encoded = Buffer.from(ps, 'utf16le').toString('base64');
    const child = exec(
      `powershell.exe -NoProfile -ExecutionPolicy Bypass -EncodedCommand ${encoded}`,
      { encoding: 'utf8', timeout: 120000, windowsHide: true },
      (err, stdout) => {
        if (err) return res.status(500).json({ error: err.message });
        const picked = (stdout || '').trim();
        // 用户取消 -> 返回空 path；正常选择 -> 返回绝对路径
        res.json({ ok: true, path: picked });
      }
    );
    // 防止句柄泄漏
    child.on('close', () => {});
  });

  // 静态仪表盘
  app.use(express.static(path.join(__dirname, 'public')));

  // ---- 错误处理 ----
  app.use((err: any, _req: Request, res: Response, _next: NextFunction) => {
    if (err instanceof HttpError) {
      return res.status(err.statusCode).json({ error: err.message });
    }
    console.error('Unhandled error', err);
    res.status(500).json({ error: 'internal' });
  });

  return app;
}
