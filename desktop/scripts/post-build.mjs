// CI/本地共用：构建后校验 NSIS 产物名符合日期 semver 命名规则。
// Tauri 默认输出 local-sharing_<version>_x64-setup.exe（version 来自 set-version 注入），
// 内部版本与文件名同源，无需重命名——这里只做存在性校验，防 CI 静默产出错名产物。
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(scriptDir, '..'); // desktop/

const nsisDir = path.join(root, 'src-tauri', 'target', 'release', 'bundle', 'nsis');
if (!fs.existsSync(nsisDir)) {
  console.error('[post-build] nsis 目录不存在：', nsisDir);
  process.exit(1);
}

const built = fs
  .readdirSync(nsisDir)
  .find((f) => /^local-sharing_\d+\.\d+\.\d+_x64-setup\.exe$/.test(f));
if (!built) {
  console.error('[post-build] 未找到 local-sharing_<semver>_x64-setup.exe 形式的产物');
  process.exit(1);
}

console.log(`[post-build] 产物校验通过：${built}`);
