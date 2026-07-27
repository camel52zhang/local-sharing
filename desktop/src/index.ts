import * as http from 'http';
import { buildApp } from './app';
import { createHub } from './ws';
import { config } from './config';
import { getLanIp, getConnectUrl } from './network';

const app = buildApp();
const server = http.createServer(app);
createHub(server);

server.listen(config.port, config.host, () => {
  const lanIp = config.discoverLanIp ? getLanIp() : null;
  const url = getConnectUrl(config.port, lanIp);
  console.log('══════════════════════════════════════════');
  console.log(`  局域网互传工具 (local-sharing) 已启动`);
  console.log(`  电脑名称 : ${config.deviceName}`);
  if (config.shareCode) console.log(`  共享码   : ${config.shareCode}`);
  console.log(`  本机访问 : http://localhost:${config.port}`);
  console.log(`  局域网地址: ${url}`);
  console.log(`  手机扫码或浏览器打开上方局域网地址即可连接`);
  console.log('══════════════════════════════════════════');
});
