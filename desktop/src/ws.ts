import { Server as HttpServer } from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import { bus, authenticate, getDevice, touchDevice, completeTransfer, listDevices, deviceIcon } from './services/services';
import { ClientMessage, ServerMessage, Transfer } from './types';

const pcSockets = new Set<WebSocket>();

export function createHub(httpServer: HttpServer): void {
  const wss = new WebSocketServer({ server: httpServer, path: '/ws' });

  wss.on('connection', (ws: WebSocket, req) => {
    const url = new URL(req.url || '', 'http://localhost');
    const token = url.searchParams.get('token');
    const role = url.searchParams.get('role');

    if (role === 'pc') {
      // 电脑端仪表盘连接，仅用于接收实时事件；仅允许本机回环（局域网设备/恶意页面不可订阅事件流）
      const addr = req.socket.remoteAddress || '';
      if (addr !== '127.0.0.1' && addr !== '::1' && addr !== '::ffff:127.0.0.1') {
        ws.close(4004, 'PC dashboard only from localhost');
        return;
      }
      pcSockets.add(ws);
      ws.on('close', () => pcSockets.delete(ws));
      ws.on('error', () => pcSockets.delete(ws));
      return;
    }

    if (!token) {
      ws.close(4001, 'Missing token');
      return;
    }
    const device = authenticate(token);
    if (!device) {
      ws.close(4003, 'Invalid token');
      return;
    }
    device.ws = ws;
    device.lastSeen = Date.now();

    send(ws, { type: 'welcome', deviceId: device.id });
    broadcastDeviceList();

    ws.on('message', (raw) => {
      try {
        const msg = JSON.parse(raw.toString()) as ClientMessage;
        if (msg.type === 'hello') touchDevice(device.id);
        if (msg.type === 'transfer-ack') completeTransfer(msg.transferId);
      } catch {
        /* ignore bad frames */
      }
    });

    ws.on('close', () => {
      device.ws = undefined;
      broadcastDeviceList();
    });
    ws.on('error', () => {
      device.ws = undefined;
      broadcastDeviceList();
    });
  });

  // ---- 订阅业务事件 ----
  bus.on('device-list', (payload: { devices: unknown }) => {
    broadcastDeviceList();
  });

  bus.on('upload-received', (payload: { files: unknown }) => {
    const files = (payload.files as any[]).map((f) => ({
      id: f.id,
      name: f.name,
      size: f.size,
      kind: f.kind,
    }));
    for (const ws of pcSockets) {
      if (ws.readyState === WebSocket.OPEN) {
        send(ws, { type: 'upload-received', files });
      }
    }
  });

  bus.on('incoming', (payload: { deviceId: string; transfer: Transfer }) => {
    const dev = getDevice(payload.deviceId);
    if (dev?.ws && (dev.ws as WebSocket).readyState === WebSocket.OPEN) {
      send(dev.ws as WebSocket, {
        type: 'incoming',
        transfer: {
          id: payload.transfer.id,
          name: payload.transfer.name,
          size: payload.transfer.size,
          kind: payload.transfer.kind,
        },
      });
    }
    // 同时通知 PC 仪表盘：出现一条“发送中”记录
    const summary = {
      id: payload.transfer.id,
      name: payload.transfer.name,
      size: payload.transfer.size,
      kind: payload.transfer.kind,
    };
    for (const ws of pcSockets) {
      if (ws.readyState === WebSocket.OPEN) {
        send(ws, { type: 'transfer-out-started', transfer: summary });
      }
    }
  });

  bus.on('transfer-completed', (payload: { transferId: string }) => {
    for (const ws of pcSockets) {
      if (ws.readyState === WebSocket.OPEN) {
        send(ws, { type: 'transfer-completed', transferId: payload.transferId });
      }
    }
  });

  bus.on('transfer-failed', (payload: { transferId: string }) => {
    for (const ws of pcSockets) {
      if (ws.readyState === WebSocket.OPEN) {
        send(ws, { type: 'transfer-failed', transferId: payload.transferId });
      }
    }
  });
}

function send(ws: WebSocket, msg: ServerMessage): void {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg));
}

function broadcastDeviceList(): void {
  const devices = listDevices().map((d) => ({
    id: d.id,
    name: d.name,
    type: d.type,
    online: !!d.ws,
    icon: deviceIcon(d.type),
  }));
  const msg: ServerMessage = { type: 'device-list', devices };
  for (const ws of pcSockets) {
    if (ws.readyState === WebSocket.OPEN) send(ws, msg);
  }
}
