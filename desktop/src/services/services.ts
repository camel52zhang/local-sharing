import { EventEmitter } from 'events';
import * as crypto from 'crypto';
import * as fs from 'fs';
import * as path from 'path';
import archiver from 'archiver';
import { config, paths } from '../config';
import {
  ActivityItem,
  Device,
  DeviceType,
  ReceivedFile,
  Transfer,
  TransferStatus,
} from '../types';

/** 服务层向实时层广播事件的轻量总线 */
export const bus = new EventEmitter();

/** PC→手机 发送后、ACK 超时阈值（毫秒）。超时未确认则标记为失败。 */
const ACK_TIMEOUT_MS = 60_000;

function id(prefix: string): string {
  return `${prefix}_${crypto.randomBytes(6).toString('hex')}`;
}

function token(): string {
  return crypto.randomBytes(16).toString('hex');
}

// ---- 设备注册表 ----
const devices = new Map<string, Device>();
const transfers = new Map<string, Transfer>();
const received: ReceivedFile[] = [];
/** 发送历史（独立于会被清理的 transfers，用于活动流展示，保留状态） */
const outgoing: Transfer[] = [];

export function registerDevice(name: string, type: DeviceType): Device {
  const device: Device = {
    id: id('dev'),
    name: name || 'Unnamed Device',
    type,
    token: token(),
    lastSeen: Date.now(),
  };
  devices.set(device.id, device);
  emitDeviceList();
  return device;
}

export function getDevice(deviceId: string): Device | undefined {
  return devices.get(deviceId);
}

export function authenticate(token: string): Device | undefined {
  for (const d of devices.values()) {
    if (d.token === token) return d;
  }
  return undefined;
}

export function touchDevice(deviceId: string): void {
  const d = devices.get(deviceId);
  if (d) d.lastSeen = Date.now();
}

export function removeDevice(deviceId: string): void {
  devices.delete(deviceId);
  emitDeviceList();
}

export function listDevices(): Device[] {
  return [...devices.values()];
}

/** 设备类型 → 展示图标（前端据此映射） */
export function deviceIcon(type: DeviceType): string {
  switch (type) {
    case 'phone':
      return 'phone';
    case 'tablet':
      return 'tablet';
    case 'pc':
      return 'pc';
    default:
      return 'device';
  }
}

export function listReceived(): ReceivedFile[] {
  return received.slice().reverse();
}

/** 合并“收到的文件(in)”与“发送历史(out)”，按时间倒序，供活动流展示 */
export function listActivity(): ActivityItem[] {
  const items: ActivityItem[] = [];
  for (const f of received) {
    items.push({
      id: f.id,
      direction: 'in',
      name: f.name,
      size: f.size,
      kind: f.kind,
      deviceName: f.fromDeviceName,
      status: 'completed',
      time: f.receivedAt,
    });
  }
  for (const t of outgoing) {
    items.push({
      id: t.id,
      direction: 'out',
      name: t.name,
      size: t.size,
      kind: t.kind,
      deviceName: '本机',
      status: t.status,
      time: t.createdAt,
    });
  }
  items.sort((a, b) => b.time - a.time);
  return items;
}

/** 删除单条“收到的文件”记录（同时删除磁盘文件，缺失则忽略） */
export function removeReceived(fileId: string): boolean {
  const idx = received.findIndex((f) => f.id === fileId);
  if (idx < 0) return false;
  const f = received[idx];
  safeUnlink(f.savedPath);
  received.splice(idx, 1);
  bus.emit('received-removed', { fileId });
  return true;
}

/** 清空“收到的文件”记录与磁盘文件 */
export function clearReceived(): void {
  for (const f of received) safeUnlink(f.savedPath);
  received.length = 0;
  bus.emit('received-removed', { fileId: '*' });
}

function emitDeviceList(): void {
  bus.emit('device-list', {
    devices: [...devices.values()].map((d) => ({
      id: d.id,
      name: d.name,
      type: d.type,
      online: !!d.ws,
    })),
  });
}

// ---- 手机 → 电脑（接收上传） ----
export interface SavedFile {
  name: string;
  savedPath: string;
  size: number;
}

/**
 * 记录手机端上传的文件。若 asFolder 为真（或本身就是 zip），标记 kind=folder。
 */
export function recordReceived(
  device: Device,
  files: SavedFile[],
  asFolder: boolean,
): ReceivedFile[] {
  const results: ReceivedFile[] = [];
  for (const f of files) {
    const isFolder = asFolder || f.name.toLowerCase().endsWith('.zip');
    const rf: ReceivedFile = {
      id: id('rcv'),
      fromDeviceName: device.name,
      name: f.name,
      size: f.size,
      kind: isFolder ? 'folder' : 'file',
      savedPath: f.savedPath,
      receivedAt: Date.now(),
    };
    received.push(rf);
    results.push(rf);
  }
  bus.emit('upload-received', { files: results });
  return results;
}

// ---- 电脑 → 手机（推送） ----
/**
 * 将 PC 端上传的文件整理为一个传输任务。
 * 多个文件或 asFolder 时打包为 zip（kind=folder），否则单文件（kind=file）。
 */
export async function finalizeOutgoing(
  deviceId: string,
  files: SavedFile[],
  asFolder: boolean,
): Promise<Transfer> {
  const target = devices.get(deviceId);
  if (!target) throw new HttpError(404, 'Target device not found');
  if (files.length === 0) throw new HttpError(400, 'No files provided');

  const transferId = id('tr');
  let filePath: string;
  let kind: 'file' | 'folder';
  let name: string;
  let size: number;

  const shouldZip = asFolder || files.length > 1;
  if (shouldZip) {
    // 用第一个文件名（去扩展名）作为压缩包名
    const base = path.basename(files[0].name, path.extname(files[0].name));
    name = `${base || 'files'}.zip`;
    filePath = path.join(paths.outbox, `${transferId}.zip`);
    await zipFiles(
      files.map((f) => ({ src: f.savedPath, entry: path.basename(f.name) })),
      filePath,
    );
    size = fs.statSync(filePath).size;
    kind = 'folder';
    // 清理中间文件
    for (const f of files) safeUnlink(f.savedPath);
  } else {
    const f = files[0];
    name = f.name;
    filePath = f.savedPath;
    size = f.size;
    kind = 'file';
  }

  const transfer: Transfer = {
    id: transferId,
    fromDeviceId: null,
    toDeviceId: deviceId,
    name,
    size,
    kind,
    status: 'ready',
    filePath,
    createdAt: Date.now(),
  };
  transfers.set(transferId, transfer);
  // 记录到发送历史（与 transfers 同一引用，状态变更会同步）
  outgoing.push(transfer);

  bus.emit('incoming', { deviceId, transfer });

  // ACK 超时：手机未在阈值内确认接收则标记为失败并清理临时文件
  setTimeout(() => {
    const t = transfers.get(transferId);
    if (t && t.status === 'ready') {
      t.status = 'failed';
      safeUnlink(t.filePath);
      transfers.delete(transferId);
      bus.emit('transfer-failed', { transferId });
    }
  }, ACK_TIMEOUT_MS).unref();

  return transfer;
}

export function getTransfer(transferId: string): Transfer | undefined {
  return transfers.get(transferId);
}

export function completeTransfer(transferId: string): void {
  const t = transfers.get(transferId);
  if (!t) return;
  t.status = 'completed';
  bus.emit('transfer-completed', { transferId });
  // 传输完成后清理临时文件
  safeUnlink(t.filePath);
  transfers.delete(transferId);
}

// ---- 工具 ----
function zipFiles(
  entries: { src: string; entry: string }[],
  dest: string,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const output = fs.createWriteStream(dest);
    const archive = archiver('zip', { zlib: { level: 9 } });
    output.on('close', () => resolve());
    archive.on('error', (err: Error) => reject(err));
    archive.pipe(output);
    for (const e of entries) {
      if (fs.existsSync(e.src)) archive.file(e.src, { name: e.entry });
    }
    archive.finalize();
  });
}

function safeUnlink(p: string): void {
  try {
    if (fs.existsSync(p)) fs.unlinkSync(p);
  } catch {
    /* ignore */
  }
}

export class HttpError extends Error {
  constructor(public statusCode: number, message: string) {
    super(message);
  }
}

export const shareCode = config.shareCode;
