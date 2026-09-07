// CI/本地共用：构建后把 Tauri NSIS 产物重命名为外部显示版命名
// local-sharing_vYY.MM.DD_x64-setup.exe（北京时间补零，与 set-version.mjs 同源）。
// exe 内部是 Cargo semver（26.9.7），产物名用显示版（v26.09.07），二者同源恒等映射。
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(scriptDir, '..'); // desktop/

const parts = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
}).format(new Date());
const [y, m, d] = parts.split('-');
const display = `v${y.slice(2)}.${m}.${d}`;

const nsisDir = path.join(root, 'src-tauri', 'target', 'release', 'bundle', 'nsis');
if (!fs.existsSync(nsisDir)) {
  console.error('[post-build] nsis 目录不存在：', nsisDir);
  process.exit(1);
}

const built = fs
  .readdirSync(nsisDir)
  .find((f) => /^local-sharing_\d+\.\d+\.\d+_x64-setup\.exe$/.test(f));
if (!built) {
  console.error('[post-build] 未找到 semver 命名的 NSIS 产物（应为 local-sharing_<26.9.7 形式>_x64-setup.exe）');
  process.exit(1);
}

const newName = `local-sharing_${display}_x64-setup.exe`;
fs.renameSync(path.join(nsisDir, built), path.join(nsisDir, newName));
console.log(`[post-build] ${built} -> ${newName}（内部 ${built.match(/[\d.]+/)[0]} 同源映射）`);
