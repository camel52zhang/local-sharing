# 第一批（止血）修复 — 独立 QA 验证报告

- 日期：2026-09-04
- 验证人：主理人齐活林（QA 子代理 429 限频失败，由主理人亲自执行）
- 范围：第一批 4 项止血修复 + `.gitignore` 补全

## 修复清单（实现：工程师寇豆码 + 主理人）
| # | 修复 | 关键位置 | 状态 |
|---|------|---------|------|
| 1 | 统一版本号 0.1.0→0.1.3 | 桌面 `package.json`/`Cargo.toml`/`tauri.conf.json` | ✅ |
| 2 | 安卓 Zip Slip（CWE-22） | `FileUtil.kt` `isSafeChild`/`isSuspiciousEntryName` + `ShareViewModel.kt:368/376` | ✅ |
| 3 | `removeReceived` 删除后落盘 | `services.ts:218` `persistAllActivity()` | ✅ |
| 4 | zip 错误传播（不再挂起/崩进程） | `services.ts:425-454` `settled`+`fail()`+`output.on('error')` | ✅ |
| 5 | `.gitignore` 补 `*.zip`/`*.exe`/`*.msi` | `.gitignore` | ✅ |

## 验证结果（全绿）
| 门 | 方法 | 结果 |
|----|------|------|
| A 类型检查 | `npm run typecheck` | 退出 0（回显 `@0.1.3`） |
| B 安卓编译 | `compileDebugKotlin` | BUILD SUCCESSFUL |
| E 版本一致 | grep `0.1.0` 于 `*.{json,toml,nsi,kt}` | 仅命中 node_modules/package-lock 第三方依赖，源码全 0.1.3 |
| C zip 错误传播 | 运行时实测（`finalizeOutgoing` 真路径，临时 DATA_DIR 隔离） | EISDIR/ENOENT → **REJECT，不挂起不崩进程**；正常 zip 回归仍通过（10 项断言全过） |
| D removeReceived 持久化 | 运行时实测 + 重启重导 | 删 r1→history.json 立即移除、r2/o1 保留；新进程重导 received 仅 `["r2"]`（**无鬼影**） |
| D2 cleanupExtracted 静态复核 | 读码 | 只删本次 `extracted` 列表 + 尾部 `outDir.delete()` 依赖 `File.delete()` 非空目录拒绝 → **不会误删用户既有文件** |

## 决策点（留存）
- **SAF `extractZipToTree` zip-bomb 上限**：暂不动，记 TODO 第三批（SAF DocumentFile 天然约束在 tree URI）。
- **`package-lock.json` 版本字段**仍 0.1.0：非阻断，下次 `npm install` 自动对齐。
- **README 虚标（断点续传/接收端解压/存储路径）**：第一批**未修**，仍待办。

## 未提交
第一批改动（含前序会话累计的未提交源码 + 本轮 4 项修复）尚未 `git commit`，等待用户确认提交范围。
