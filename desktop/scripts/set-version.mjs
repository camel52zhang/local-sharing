// CI/本地共用：构建前按「北京时间当天日期」写入版本号（双轨制，同一日期派生两个形式）：
// - 内部 semver（Cargo/Tauri 硬约束，无 v 前缀/前导零）：26.9.7 -> tauri.conf/Cargo/package.json（烧入 exe）
// - 外部显示版（vYY.MM.DD 补零）：v26.09.07 -> src/version.ts（/api/info 应用内显示）与产物文件名（post-build 重命名）
// 两者同源呼应：26.9.7 <-> v26.09.07 恒等映射。
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(scriptDir, '..'); // desktop/

// 北京时间（UTC+8）当天日期，避免 CI 机器 UTC 时区导致与安卓端（TZ=Asia/Shanghai）不同日
const parts = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
}).format(new Date()); // "2026-09-07"
const [y, m, d] = parts.split('-');
const yy = y.slice(2);

const version = `${yy}.${parseInt(m, 10)}.${parseInt(d, 10)}`; // 26.9.7，Cargo 合法 semver
const display = `v${yy}.${m}.${d}`; // v26.09.07，补零显示版（文件名/应用内展示）

console.log(`[set-version] internal=${version} display=${display}`);

// 1) tauri.conf.json（应用内嵌版本，编译时烧入二进制）
const confPath = path.join(root, 'src-tauri', 'tauri.conf.json');
const conf = JSON.parse(fs.readFileSync(confPath, 'utf-8'));
conf.version = version;
fs.writeFileSync(confPath, JSON.stringify(conf, null, 2) + '\n', 'utf-8');

// 2) Cargo.toml（[package] 下的 version 行，必须合法 semver）
const cargoPath = path.join(root, 'src-tauri', 'Cargo.toml');
const cargo = fs.readFileSync(cargoPath, 'utf-8');
fs.writeFileSync(cargoPath, cargo.replace(/^version = ".*"/m, `version = "${version}"`), 'utf-8');

// 3) package.json
const pkgPath = path.join(root, 'package.json');
const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8'));
pkg.version = version;
fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n', 'utf-8');

// 4) src/version.ts（服务端 /api/info 返回的应用版本，显示版与产物文件名一致）
const versionTsPath = path.join(root, 'src', 'version.ts');
fs.writeFileSync(
  versionTsPath,
  `// 由 scripts/set-version.mjs 在构建时自动写入（北京时间日期），无需手动修改。\n` +
    `// 显示版 ${display} 与产物文件名 local-sharing_${display}_x64-setup.exe / .apk 一致；\n` +
    `// 内部 semver（Cargo 烧入 exe）为 ${version}，二者同源恒等映射。\n` +
    `export const APP_VERSION = '${display}'\n`,
  'utf-8',
);

console.log('[set-version] done');
