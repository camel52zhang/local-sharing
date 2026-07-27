import express, { Request, Response, NextFunction } from 'express';
import cors from 'cors';
import multer from 'multer';
import * as fs from 'fs';
import * as path from 'path';
import QRCode from 'qrcode';
import { config, paths, ensureDirs } from './config';
import { getLanIp, getConnectUrl } from './network';
import {
  registerDevice,
  getDevice,
  authenticate,
  listDevices,
  listReceived,
  recordReceived,
  finalizeOutgoing,
  getTransfer,
  completeTransfer,
  HttpError,
  shareCode,
} from './services/services';
import { DeviceType } from './types';

ensureDirs();

const lanIp = config.discoverLanIp ? getLanIp() : null;
const connectUrl = getConnectUrl(config.port, lanIp);

function sanitizeName(name: string): string {
  const base = path.basename(name).replace(/[\/\\]/g, '_');
  return base.replace(/[^\w.\-\u4e00-\u9fa5 ]/g, '_') || 'file';
}

const uploadStorage = multer.diskStorage({
  destination: (req, _file, cb) => {
    const deviceId = (req.headers['x-device-id'] as string) || 'anonymous';
    const dir = path.join(paths.received, deviceId);
    fs.mkdirSync(dir, { recursive: true });
    cb(null, dir);
  },
  filename: (_req, file, cb) => cb(null, sanitizeName(file.originalname)),
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
    const device = registerDevice(name, type);
    res.json({
      deviceId: device.id,
      token: device.token,
      wsUrl: `${connectUrl.replace('http', 'ws')}/ws?token=${device.token}`,
    });
  });

  // ---- 设备列表（电脑端仪表盘） ----
  app.get('/api/devices', (_req, res) => {
    res.json({
      devices: listDevices().map((d) => ({
        id: d.id,
        name: d.name,
        type: d.type,
        online: !!d.ws,
      })),
    });
  });

  // ---- 接收文件列表 ----
  app.get('/api/received', (_req, res) => {
    res.json({ files: listReceived() });
  });

  // ---- 手机 → 电脑：上传 ----
  app.post('/api/upload', (req: any, res, next) => {
    upload.any()(req, res, (err: any) => {
      if (err) return next(new HttpError(400, err.message));
      try {
        const device = authenticate(req.headers['x-token'] as string);
        if (!device) return res.status(401).json({ error: 'unauthorized' });
        const files = (req.files || []).map((f: any) => ({
          name: f.originalname,
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
            name: f.originalname,
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
