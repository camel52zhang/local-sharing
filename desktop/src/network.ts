import * as os from 'os';

/**
 * 探测局域网 IPv4 地址（排除回环、虚拟网卡、链路本地）。
 * 用于在界面/二维码中展示手机应当连接的主机地址。
 */
export function getLanIp(): string | null {
  const ifaces = os.networkInterfaces();

  // 虚拟/隧道/容器网卡关键词（命中则直接跳过，它们通常不是手机应当连接的真实网卡）
  const VIRTUAL =
    /virtual|vmware|vbox|docker|veth|br-|lo|loopback|tap|tun|\bvpn\b|wg|wireguard|openvpn|zero-?tier|tailscale|utun|ppp|vethernet|hyper-?v|wsl|bluetooth|蓝牙|isatap|teredo|6to4|pseudo/i;
  // 真实物理网卡命名（以太网 / Wi‑Fi / 常见中英文名）
  const PHYSICAL =
    /wi-?fi|wlan|wireless|ethernet|以太网|本地连接|无线网络连接|eth\d*|^en\d|^wlan\d|^eth/i;

  const physical: string[] = [];
  const others: string[] = [];

  for (const name of Object.keys(ifaces)) {
    const list = ifaces[name];
    if (!list) continue;
    const lower = name.toLowerCase();
    // 先按名字过滤掉虚拟/隧道网卡
    if (VIRTUAL.test(lower)) continue;
    for (const ni of list) {
      if (ni.family !== 'IPv4' || ni.internal) continue;
      // 跳过链路本地 169.254.x.x
      if (ni.address.startsWith('169.254.')) continue;
      if (PHYSICAL.test(lower)) physical.push(ni.address);
      else others.push(ni.address);
    }
  }

  // 优先真实物理网卡；其次其它非虚拟网卡；仍没有则兜底遍历所有非回环 IPv4
  if (physical.length > 0) return physical[0];
  if (others.length > 0) return others[0];

  // 兜底：极少数机器全部是虚拟网卡时，仍能给出某个非回环 IPv4
  for (const name of Object.keys(ifaces)) {
    for (const ni of ifaces[name] || []) {
      if (
        ni.family === 'IPv4' &&
        !ni.internal &&
        !ni.address.startsWith('169.254.')
      ) {
        return ni.address;
      }
    }
  }
  return null;
}

export function getConnectUrl(port: number, lanIp: string | null): string {
  const host = lanIp ?? 'localhost';
  return `http://${host}:${port}`;
}
