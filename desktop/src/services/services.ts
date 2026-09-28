import { EventEmitter } from 'events';
import * as crypto from 'crypto';
import * as fs from 'fs';
import * as path from 'path';
import archiver from 'archiver';
import { config, paths, loadSettings, loadHistory, appendHistory, appendHistoryBulk, saveHistory, clearHistory, updateSettings } from '../config';
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

/**
 * PC→手机 发送后、ACK 超时阈值。
 * 固定 60s 会让大文件必失败（手机还没拉完就被判死并删除临时文件），
 * 因此：基准 60s + 按文件大小给传输时间（按保守 1MB/s 拉取速率估算，每 MB 追加 1s，另加 30s 余量）。
 */
const ACK_BASE_TIMEOUT_MS = 60_000;
const ACK_TIMEOUT_PER_MB_MS = 1_000;
const ACK_TIMEOUT_HEADROOM_MS = 30_000;

function ackTimeoutMs(sizeBytes: number): number {
  const mb = Math.ceil(sizeBytes / (1024 * 1024));
  return ACK_BASE_TIMEOUT_MS + mb * ACK_TIMEOUT_PER_MB_MS + ACK_TIMEOUT_HEADROOM_MS;
}

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

/**
 * 启动时从 data/history.json 回填内存中的 received / outgoing，
 * 使活动流（收发记录）在应用重启后完整恢复。历史损坏时静默回退空。
 */
(function hydrateFromHistory(): void {
  const items = loadHistory();
  for (const it of items) {
    if (it.direction === 'in') {
      received.push({
        id: it.id,
        fromDeviceName: it.deviceName,
        name: it.name,
        size: it.size,
        kind: it.kind,
        savedPath: it.savedPath || '',
        receivedAt: it.time,
      });
    } else {
      outgoing.push({
        id: it.id,
        fromDeviceId: null,
        toDeviceId: '',
        name: it.name,
        size: it.size,
        kind: it.kind,
        status: it.status,
        filePath: '',
        createdAt: it.time,
      });
    }
  }
})();

/**
 * 将一条活动项追加进持久化历史（变更即落盘）。
 * 返回最新历史数组。
 */
function persistActivity(item: ActivityItem): ActivityItem[] {
  return appendHistory(item);
}

/** 用当前内存中的 received / outgoing 重建并落盘完整活动流（用于批量变更后同步） */
function persistAllActivity(): void {
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
      savedPath: f.savedPath,
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
  // 按时间倒序，展示一致
  items.sort((a, b) => b.time - a.time);
  // 一次性全量落盘（此前逐条 appendHistory 会造成 O(N²) 的读-改-写）
  saveHistory(items);
}

/** 取设备别名（clientId -> 自定义名，持久化在 settings.json） */
function getAlias(deviceId: string): string {
  return loadSettings().deviceAliases[deviceId] || '';
}

export function registerDevice(name: string, type: DeviceType, clientId?: string): Device {
  // 展示名 = 别名（若有，重连后自动沿用）|| 设备上报名
  const alias = clientId ? getAlias(clientId) : '';
  const displayName = alias || name || 'Unnamed Device';
  // 同一 clientId（同设备）再次注册时复用既有条目，仅刷新 name/token/lastSeen，
  // 不再新建，避免同手机反复连接产生多个 Device。
  if (clientId && devices.has(clientId)) {
    const existing = devices.get(clientId)!;
    existing.reportedName = name || 'Unnamed Device';
    existing.name = displayName;
    existing.token = token();
    existing.lastSeen = Date.now();
    emitDeviceList();
    return existing;
  }
  const device: Device = {
    id: clientId && clientId.length > 0 ? clientId : id('dev'),
    name: displayName,
    reportedName: name || 'Unnamed Device',
    type,
    token: token(),
    lastSeen: Date.now(),
  };
  devices.set(device.id, device);
  emitDeviceList();
  return device;
}

/**
 * 重命名设备：写入持久化别名，重连后自动沿用。
 * 名称为空或等于设备上报名时视为清除别名（回退上报名）。
 */
export function renameDevice(deviceId: string, rawName: string): Device | undefined {
  const d = devices.get(deviceId);
  if (!d) return undefined;
  const name = rawName.trim();
  const s = loadSettings();
  if (!name || name === d.reportedName) {
    delete s.deviceAliases[deviceId];
    d.name = d.reportedName;
  } else {
    s.deviceAliases[deviceId] = name;
    d.name = name;
  }
  updateSettings({ deviceAliases: s.deviceAliases });
  emitDeviceList();
  return d;
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

/** 合并"收到的文件(in)"与"发送历史(out)"，按时间倒序，供活动流展示 */
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
      savedPath: f.savedPath,
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

/** 删除单条"收到的文件"记录（同时删除磁盘文件，缺失则忽略） */
export function removeReceived(fileId: string): boolean {
  const idx = received.findIndex((f) => f.id === fileId);
  if (idx < 0) return false;
  const f = received[idx];
  safeUnlink(f.savedPath);
  received.splice(idx, 1);
  // 同步持久化历史，避免删除的记录在重启后从 history.json 复活（与 clearReceived 保持一致）
  persistAllActivity();
  bus.emit('received-removed', { fileId });
  return true;
}

/** 清空"收到的文件"记录与磁盘文件 */
export function clearReceived(): void {
  for (const f of received) safeUnlink(f.savedPath);
  received.length = 0;
  // 同步持久化历史（仅清收到的部分，不影响发送历史）
  persistAllActivity();
  bus.emit('received-removed', { fileId: '*' });
}

/**
 * 清空全部历史（收到的 + 发送的）。
 * 注意：仅清记录，不删除磁盘上已落盘的实际文件（防误删）。
 * 清空后历史文件置空，emit 'history-cleared' 供前端刷新。
 */
export function clearAllHistory(): void {
  // 仅清内存视图，不触碰磁盘文件
  received.length = 0;
  outgoing.length = 0;
  for (const t of transfers.keys()) {
    const t2 = transfers.get(t);
    if (t2) safeUnlink(t2.filePath);
    transfers.delete(t);
  }
  clearHistory();
  bus.emit('history-cleared', {});
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
  const historyItems: ActivityItem[] = [];
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
    historyItems.push({
      id: rf.id,
      direction: 'in',
      name: rf.name,
      size: rf.size,
      kind: rf.kind,
      deviceName: rf.fromDeviceName,
      status: 'completed',
      time: rf.receivedAt,
      savedPath: rf.savedPath,
    });
  }
  // 多文件只落盘一次（逐条 appendHistory 会造成 O(N²) 读-改-写）
  if (historyItems.length > 0) appendHistoryBulk(historyItems);
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

  // 落盘发送活动记录，并记忆“上次发送设备”
  persistActivity({
    id: transfer.id,
    direction: 'out',
    name: transfer.name,
    size: transfer.size,
    kind: transfer.kind,
    deviceName: '本机',
    status: transfer.status,
    time: transfer.createdAt,
  });
  try {
    updateSettings({ lastDeviceId: deviceId });
  } catch {
    /* 记忆失败不致命 */
  }

  bus.emit('incoming', { deviceId, transfer });

  // ACK 超时：手机未在阈值内确认接收则标记为失败并清理临时文件
  setTimeout(() => {
    const t = transfers.get(transferId);
    if (t && t.status === 'ready') {
      t.status = 'failed';
      safeUnlink(t.filePath);
      transfers.delete(transferId);
      // 同步更新持久化历史中的状态
      persistAllActivity();
      bus.emit('transfer-failed', { transferId });
    }
  }, ackTimeoutMs(transfer.size)).unref();

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
  // 同步更新持久化历史中的状态
  persistAllActivity();
}

// ---- 工具 ----
function zipFiles(
  entries: { src: string; entry: string }[],
  dest: string,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const output = fs.createWriteStream(dest);
    const archive = archiver('zip', { zlib: { level: 9 } });
    // archive 与 output 可能同时报错，用标志位保证 settle 只发生一次，
    // 避免二次 reject/resolve 触发 unhandled rejection
    let settled = false;

    /** 失败收尾：中止归档、关闭写流、删除半截 zip，然后 reject（仅生效一次） */
    const fail = (err: Error): void => {
      if (settled) return;
      settled = true;
      try {
        archive.abort();
      } catch {
        /* archiver 已结束/不支持 abort 时忽略 */
      }
      try {
        output.destroy();
      } catch {
        /* 流已关闭时忽略 */
      }
      // 清理半截 zip，避免残留损坏文件
      safeUnlink(dest);
      reject(err);
    };

    output.on('close', () => {
      if (settled) return;
      settled = true;
      resolve();
    });
    // 必须监听写流错误：磁盘满/权限不足时 close 不会触发，
    // 若无人接管 error，Promise 将永不 settle，HTTP 请求会永久挂起
    output.on('error', (err: Error) => fail(err));
    archive.on('error', (err: Error) => fail(err));

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
