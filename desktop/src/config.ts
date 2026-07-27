import * as path from 'path';
import * as fs from 'fs';

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
