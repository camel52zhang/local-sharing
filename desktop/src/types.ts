export type DeviceType = 'phone' | 'tablet' | 'pc' | 'unknown';

export interface Device {
  id: string;
  name: string;
  type: DeviceType;
  token: string;
  lastSeen: number;
  /** 运行时持有 WebSocket 连���（不序列化） */
  ws?: unknown;
}

export type TransferKind = 'file' | 'folder';
export type TransferStatus = 'pending' | 'ready' | 'downloading' | 'completed' | 'failed';

/** 活动流条目：合并"收到的文件(in)"与"发送历史(out)" */
export interface ActivityItem {
  id: string;
  direction: 'in' | 'out';
  name: string;
  size: number;
  kind: TransferKind;
  /** in: 来源设备名；out: 始终为"本机" */
  deviceName: string;
  status: TransferStatus;
  time: number;
  /** in 方向时记录服务器上的实际存储路径，供"打开文件夹"使用 */
  savedPath?: string;
}

export interface Transfer {
  id: string;
  fromDeviceId: string | null; // null 表示来自电脑端
  toDeviceId: string;
  name: string;
  size: number;
  kind: TransferKind;
  status: TransferStatus;
  /** 服务器上的临时文件路径（outbox） */
  filePath: string;
  createdAt: number;
}

export interface ReceivedFile {
  id: string;
  fromDeviceName: string;
  name: string;
  size: number;
  kind: TransferKind;
  savedPath: string;
  receivedAt: number;
}

/** WebSocket 消息协议 */
export type ServerMessage =
  | { type: 'welcome'; deviceId: string }
  | { type: 'device-list'; devices: DeviceInfo[] }
  | { type: 'incoming'; transfer: TransferInfo }
  | { type: 'upload-received'; files: ReceivedFileInfo[] }
  | { type: 'transfer-out-started'; transfer: TransferInfo }
  | { type: 'transfer-completed'; transferId: string }
  | { type: 'transfer-failed'; transferId: string };

export type ClientMessage =
  | { type: 'hello' }
  | { type: 'transfer-ack'; transferId: string };

export interface DeviceInfo {
  id: string;
  name: string;
  type: DeviceType;
  online: boolean;
  /** 展示用图标字符，前端据此映射 */
  icon?: string;
}

export interface TransferInfo {
  id: string;
  name: string;
  size: number;
  kind: TransferKind;
}

export interface ReceivedFileInfo {
  id: string;
  name: string;
  size: number;
  kind: TransferKind;
}
