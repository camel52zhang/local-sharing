import * as os from 'os';

/**
 * 探测局域网 IPv4 地址（排除回环、虚拟网卡、链路本地）。
 * 用于在界面/二维码中展示手机应当连接的主机地址。
 */
export function getLanIp(): string | null {
  const ifaces = os.networkInterfaces();
  const candidates: string[] = [];

  for (const name of Object.keys(ifaces)) {
    const list = ifaces[name];
    if (!list) continue;
    for (const ni of list) {
      if (ni.family !== 'IPv4' || ni.internal) continue;
      const addr = ni.address;
      // 跳过链路本地 169.254.x.x
      if (addr.startsWith('169.254.')) continue;
      // 跳过常见虚拟网卡关键词
      const lower = name.toLowerCase();
      if (/virtual|vmware|vbox|docker|veth|br-|lo|loopback/.test(lower)) continue;
      candidates.push(addr);
    }
  }

  if (candidates.length === 0) {
    // 兜底：遍历所有 IPv4 非回环
    for (const name of Object.keys(ifaces)) {
      const list = ifaces[name];
      if (!list) continue;
      for (const ni of list) {
        if (ni.family === 'IPv4' && !ni.internal) candidates.push(ni.address);
      }
    }
  }

  return candidates[0] ?? null;
}

export function getConnectUrl(port: number, lanIp: string | null): string {
  const host = lanIp ?? 'localhost';
  return `http://${host}:${port}`;
}
