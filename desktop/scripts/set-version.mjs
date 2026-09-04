// CI/本地共用：构建前按「北京时间当天日期」写入版本号。
// 约束：Cargo/Tauri 要求严格 semver（不接受前导零与 v 前缀），
// 因此内部 semver 取去零日期（如 26.9.4）；显示名 v26.09.04（含前导零）由
// src/version.ts（进程内展示）与 post-build.mjs（产物文件名）负责，见 README 命名规则。
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(scriptDir, '..'); // desktop/

// 北京时间（UTC+8）当天日期，避免 CI 机器 UTC 时区导致日期与安卓端（TZ=Asia/Shanghai）不一致
const parts = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
}).format(new Date()); // "2026-09-04"
const [y, m, d] = parts.split('-');
const yy = y.slice(2);

const display = `v${yy}.${m}.${d}`; // 26.09.04 补零，用于展示/文件名
const semver = `${yy}.${parseInt(m, 10)}.${parseInt(d, 10)}`; // 26.9.4 去零，Cargo 合法 semver

console.log(`[set-version] display=${display} semver=${semver}`);

// 1) tauri.conf.json（应用内嵌版本，构建时烧入二进制）
const confPath = path.join(root, 'src-tauri', 'tauri.conf.json');
const conf = JSON.parse(fs.readFileSync(confPath, 'utf-8'));
conf.version = semver;
fs.writeFileSync(confPath, JSON.stringify(conf, null, 2) + '\n', 'utf-8');

// 2) Cargo.toml（[package] 下的 version 行，必须合法 semver）
const cargoPath = path.join(root, 'src-tauri', 'Cargo.toml');
const cargo = fs.readFileSync(cargoPath, 'utf-8');
fs.writeFileSync(cargoPath, cargo.replace(/^version = ".*"/m, `version = "${semver}"`), 'utf-8');

// 3) package.json
const pkgPath = path.join(root, 'package.json');
const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8'));
pkg.version = semver;
fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n', 'utf-8');

// 4) src/version.ts（服务端 /api/info 返回的展示版本，与产物文件名严格一致）
const versionTsPath = path.join(root, 'src', 'version.ts');
fs.writeFileSync(
  versionTsPath,
  `// 由 scripts/set-version.mjs 在构建时自动写入（北京时间日期），无需手动修改。\n` +
    `// 显示版本 v${yy}.${m}.${d} 与产物文件名 local-sharing_${display}_x64-setup.exe 严格一致；\n` +
    `// 内部 semver（Cargo 约束）为 ${semver}（去前导零）。\n` +
    `export const APP_VERSION = '${display}'\n`,
  'utf-8',
);

console.log('[set-version] done');
