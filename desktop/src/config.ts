import * as path from 'path';
import * as fs from 'fs';
import { ActivityItem } from './types';

function required(name: string, fallback?: string): string {
  const v = process.env[name];
  if (v === undefined || v === '') {
    if (fallback !== undefined) return fallback;
    throw new Error(`Missing required env var: ${name}`);
  }
  return v;
}

function intEnv(name: string, fallback: number): number {
  const v = process.env[name];
  if (v === undefined || v === '') return fallback;
  const n = parseInt(v, 10);
  if (Number.isNaN(n)) throw new Error(`Env var ${name} must be an integer`);
  return n;
}

// 加载 .env（若存在），失败不致命
try {
  const envPath = path.resolve(process.cwd(), '.env');
  if (fs.existsSync(envPath)) {
    const raw = fs.readFileSync(envPath, 'utf-8');
    for (const line of raw.split('\n')) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
      if (m && process.env[m[1]] === undefined) {
        process.env[m[1]] = m[2].replace(/^["']|["']$/g, '');
      }
    }
  }
} catch {
  // ignore
}

export const config = {
  port: intEnv('PORT', 8080),
  host: required('HOST', '0.0.0.0'),
  deviceName: required('DEVICE_NAME', 'My Computer'),
  shareCode: process.env['SHARE_CODE'] || '',
  maxFileSize: intEnv('MAX_FILE_SIZE', 2 * 1024 * 1024 * 1024),
  dataDir: path.resolve(process.cwd(), required('DATA_DIR', './data')),
  discoverLanIp: process.env['DISCOVER_LAN_IP'] !== 'false',
} as const;

export const paths = {
  received: path.join(config.dataDir, 'received'),
  outbox: path.join(config.dataDir, 'outbox'),
};

export function ensureDirs(): void {
  fs.mkdirSync(paths.received, { recursive: true });
  fs.mkdirSync(paths.outbox, { recursive: true });
}

// ---- 用户设置持久化 ----

export interface Settings {
  downloadDir: string;
  /** 上次发送成功的目标设备 id；用于拖拽即发时记忆上次设备 */
  lastDeviceId: string;
}

export const settingsPath = path.join(config.dataDir, 'settings.json');

export function loadSettings(): Settings {
  try {
    if (fs.existsSync(settingsPath)) {
      const parsed = JSON.parse(fs.readFileSync(settingsPath, 'utf-8'));
      return {
        downloadDir: typeof parsed.downloadDir === 'string' ? parsed.downloadDir : '',
        lastDeviceId: typeof parsed.lastDeviceId === 'string' ? parsed.lastDeviceId : '',
      };
    }
  } catch {
    // ignore corrupt settings
  }
  return { downloadDir: '', lastDeviceId: '' };
}

export function saveSettings(s: Settings): void {
  fs.writeFileSync(settingsPath, JSON.stringify(s, null, 2), 'utf-8');
}

export function updateSettings(patch: Partial<Settings>): Settings {
  const s = loadSettings();
  if (patch.downloadDir !== undefined) s.downloadDir = patch.downloadDir;
  if (patch.lastDeviceId !== undefined) s.lastDeviceId = patch.lastDeviceId;
  saveSettings(s);
  return s;
}

// ---- 活动流持久化（历史记录） ----

/** 历史记录落盘路径，与 settings.json 同级 */
export const historyPath = path.join(config.dataDir, 'history.json');

/**
 * 读取持久化的活动流。
 * 文件损坏或不存在时回退为空数组（不致命，沿用 loadSettings 的容错范式）。
 */
export function loadHistory(): ActivityItem[] {
  try {
    if (fs.existsSync(historyPath)) {
      const raw = JSON.parse(fs.readFileSync(historyPath, 'utf-8'));
      if (Array.isArray(raw)) return raw as ActivityItem[];
    }
  } catch {
    // ignore corrupt history, fall back to empty
  }
  return [];
}

/** 全量覆盖写入活动流 */
export function saveHistory(items: ActivityItem[]): void {
  try {
    fs.writeFileSync(historyPath, JSON.stringify(items, null, 2), 'utf-8');
  } catch {
    // 写历史失败不阻断主流程
  }
}

/** 追加单条活动并落盘（保持时间倒序由调用方负责） */
export function appendHistory(item: ActivityItem): ActivityItem[] {
  const items = loadHistory();
  items.push(item);
  saveHistory(items);
  return items;
}

/**
 * 批量追加活动并一次性落盘。
 * 逐条 appendHistory 会造成 O(N²) 的全量读-改-写，多文件上传时改用本函数。
 */
export function appendHistoryBulk(items: ActivityItem[]): ActivityItem[] {
  if (items.length === 0) return loadHistory();
  const all = loadHistory();
  all.push(...items);
  saveHistory(all);
  return all;
}

/** 清空全部历史（仅清记录，不删磁盘实际文件） */
export function clearHistory(): ActivityItem[] {
  saveHistory([]);
  return [];
}

/** 获取实际使用的下载目录：用户自定义优先，否则默认 received 目录 */
export function getEffectiveDownloadDir(): string {
  const settings = loadSettings();
  if (settings.downloadDir && fs.existsSync(settings.downloadDir)) {
    return settings.downloadDir;
  }
  return paths.received;
}
