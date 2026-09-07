// CI/本地共用：构建前按「北京时间当天日期」写入版本号。
// 版本统一为 semver 日期形式 YY.M.D（去前导零、无 v 前缀，Cargo/Tauri 硬约束），
// 全链路逐字符一致：exe 内嵌 = exe 属性 = 注册表 = 产物文件名 = /api/info = APK versionName。
// 例：2026-09-07 -> 26.9.7 -> local-sharing_26.9.7_x64-setup.exe / local-sharing_26.9.7.apk
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

// 去前导零的 semver 日期：26.9.7（唯一合法且全链路一致的形式）
const version = `${yy}.${parseInt(m, 10)}.${parseInt(d, 10)}`;

console.log(`[set-version] version=${version}`);

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

// 4) src/version.ts（服务端 /api/info 返回的应用版本，与产物文件名逐字符一致）
const versionTsPath = path.join(root, 'src', 'version.ts');
fs.writeFileSync(
  versionTsPath,
  `// 由 scripts/set-version.mjs 在构建时自动写入（北京时间日期，semver 形式），无需手动修改。\n` +
    `// 与产物文件名（local-sharing_${version}_x64-setup.exe / local-sharing_${version}.apk）逐字符一致。\n` +
    `export const APP_VERSION = '${version}'\n`,
  'utf-8',
);

console.log('[set-version] done');
