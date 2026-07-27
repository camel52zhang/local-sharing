export type DeviceType = 'phone' | 'tablet' | 'pc' | 'unknown';

export interface Device {
  id: string;
  name: string;
  type: DeviceType;
  token: string;
  lastSeen: number;
  /** 运行时持有 WebSocket 连接（不序列化） */
  ws?: unknown;
}

export type TransferKind = 'file' | 'folder';
export type TransferStatus = 'pending' | 'ready' | 'downloading' | 'completed' | 'failed';

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
  | { type: 'upload-received'; file: ReceivedFileInfo }
  | { type: 'transfer-completed'; transferId: string };

export type ClientMessage =
  | { type: 'hello' }
  | { type: 'transfer-ack'; transferId: string };

export interface DeviceInfo {
  id: string;
  name: string;
  type: DeviceType;
  online: boolean;
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
