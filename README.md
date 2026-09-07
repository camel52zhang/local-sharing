# 局域网互传工具 · local-sharing

> 一款运行在局域网内的文件/文件夹互传工具：手机/平板扫码或输入电脑 IP 即可连接电脑，
> 双向互传文件与文件夹，无需公网、无需注册、无需云。

- **中文名**：局域网互传工具
- **英文名**：local-sharing
- **当前版本**：桌面端安装包 `v0.1.3` · 安卓端 `v0.1.3`
- **形态**：桌面端为 Tauri 打包的独立桌面应用（Windows x64）；移动端为 Android App（Kotlin + Jetpack Compose）

---

## ✨ 功能特性

1. **手机 → 电脑**：局域网内手机拍的照片、编辑的文件、文件夹，扫码或输入电脑 IP 连接后，直接发送到电脑（自动按设备归类存放于 `data/received/<设备>/`）。
2. **电脑 → 手机/平板**：电脑端选择文件或文件夹，推送到已连接的手机/平板，手机端实时收到并保存。
3. **双向文件夹传输**：文件夹在发送端自动打包为 zip；安卓接收端自动解压还原目录结构，电脑接收端保留 zip 压缩包（标记为文件夹类型）。
4. **扫码直连**：电脑端界面展示二维码，手机扫码即获取连接地址；也支持手动输入 `IP:端口`。
5. **可选共享码**：开放 WiFi 环境下可设置共享码，防止陌生设备接入。
6. **系统分享入口**：手机端已注册为系统分享目标——在相册、文件管理器等任意 App 中「分享」文件时可直接选本应用，连接电脑后自动填入发送列表。
7. **接收端自定义保存目录**：手机端可指定文件保存位置（SAF 选目录，持久记忆），并支持「自动保存」开关。
8. **传输活动历史**：电脑端记录收发活动流，便于回看。
9. **诊断日志（移动端，调试包专属）**：详见下方「🐞 诊断日志」一节。

---

## 🚀 快速开始（三步传文件）

1. **电脑装好并启动**「局域网互传工具」桌面端 → 界面显示二维码与局域网地址。
2. **手机安装** `local-sharing` App → 打开后点「扫码连接」扫描电脑二维码（或手动输入 `电脑IP:8080`）。
3. **传文件**：
   - 手机 → 电脑：在手机端「发送到电脑」选文件/文件夹 → 发送，电脑端自动接收。
   - 电脑 → 手机：在电脑端「发送到设备」选文件/文件夹 → 发送，手机端实时收到并可保存。

---

## 🏗 架构

以**电脑为中心**的星型拓扑（手机/平板不做服务端，规避 Android 后台服务与网络限制）：

```
        ┌──────────────┐   HTTP 上传    ┌──────────────────────┐
        │  手机/平板    │ ─────────────▶ │                      │
        │ (Android App) │                │   电脑端服务          │
        │              │ ◀──── 下载 ──── │  (Node.js + Express) │
        │  WebSocket   │   (WS 通知)     │   + Web 仪表盘        │
        └──────────────┘                │   (Tauri 外壳)        │
              ▲                          └──────────────────────┘
              │  扫码 / 手动输入 IP:端口
              │
        ┌─────┴────────┐
        │  电脑端二维码 │  http://<LAN_IP>:<PORT>
        └──────────────┘
```

- **电脑端**：Tauri（Rust 外壳）内嵌 Node.js Express 服务 + Web 仪表盘；Node 以 `node.exe` sidecar 形式随应用分发，干净机器无需预装 Node。
- **实时通道**：手机通过 WebSocket 连接到电脑，用于设备在线状态与「电脑 → 手机」推送通知。
- **文件方向**：
  - 手机 → 电脑：`POST /api/upload`（multipart），服务端存入下载目录（默认 `data/received`，可自定义）下的 `local-sharing-files/` 子目录，同名文件自动加序号。
  - 电脑 → 手机：电脑端先上传到服务端 outbox，服务端经 WS 通知目标设备，手机再 `GET /api/transfer/:id` 拉取。
- **文件夹**：发送端打包 zip；安卓接收端自动解压，电脑接收端保留 zip（标记为文件夹）。

**技术栈**

| 端 | 技术 |
|----|------|
| 桌面端 | Tauri v2 + Rust（外壳）、Node.js + Express + ws（服务）、原生 HTML/CSS/JS（仪表盘） |
| 移动端 | Kotlin + Jetpack Compose（Material 3）、OkHttp（HTTP/上传）、原生 WebSocket、CameraX + ML Kit（扫码）、SAF（目录访问） |

---

## 📁 目录结构

```
local-sharing/
├── desktop/                      # 电脑端（Tauri + Node.js 服务）
│   ├── package.json              # 脚本：dev / build / start / tauri:build
│   ├── tsconfig.json
│   ├── scripts/
│   │   └── bundle-server.mjs     # tauri 构建前：编译服务并打包进 resources/server
│   ├── src/                      # Node 服务源码（index.ts/app.ts/ws.ts/config.ts/services…）
│   │   └── public/               # Web 仪表盘（HTML/CSS/JS）
│   ├── src-tauri/                # Tauri 外壳（Rust）
│   │   ├── tauri.conf.json       # 应用配置、打包目标、资源
│   │   ├── resources/server/     # 构建产物：Node 服务（dist + node_modules）
│   │   ├── binaries/node-*.exe   # Node sidecar
│   │   └── resources/WebView2Loader.dll  # WebView2 加载器（GNU 目标启动必需）
│   ├── build-installer.nsi       # 离线重打包用的 NSIS 脚本（可选）
│   └── test/
└── android/                      # 安卓端（Kotlin + Compose）
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── app/src/main/
        ├── AndroidManifest.xml   # 含系统分享 intent-filter
        ├── java/com/localsharing/app/
        │   ├── ui/               # 界面（连接/主页/扫码/诊断日志）
        │   ├── network/          # HTTP 与 WebSocket 封装
        │   ├── viewmodel/        # ShareViewModel（状态与逻辑）
        │   ├── util/             # 文件工具、崩溃日志收集
        │   └── model/            # 数据模型
        └── res/
```

---

## 💻 桌面端

### 方式一：安装包（推荐，Tauri 打包）

已发布安装包与便携版（当前 `v0.1.3`），直接取用即可：

- `local-sharing_<YY.M.D>_x64-setup.exe` —— NSIS 安装包（当前用户模式安装，无需管理员；版本=构建日期的 semver 形式，与 exe 内嵌版本逐字符一致）。
- `local-sharing_<YY.M.D>_x64-portable.zip` —— 免安装便携版，解压即跑（版本=构建日期的 semver 形式）。

**安装与运行**

1. 双击 `setup.exe`，按向导安装（默认装到当前用户 `AppData\Local\local-sharing`）。
2. 安装结束页有两个默认勾选项：
   - ✅ **Run local-sharing**（结束并立即启动）
   - ✅ **Create desktop shortcut**（创建桌面快捷方式）
3. 首次启动若系统缺少 WebView2 运行时，安装包内置的引导器会**自动下载并安装** WebView2（需联网；离线环境可手动安装 WebView2 运行时）。
4. 启动后桌面端窗口显示：本机局域网地址、二维码、已连接设备、收到的文件、「发送到设备」面板。
5. 卸载：系统「设置 → 应用」中找到 local-sharing 卸载即可（便携版直接删文件夹）。

> 安装目录结构：`local-sharing-desktop.exe` + `server/`（Node 服务）+ `node.exe`（sidecar）+ `WebView2Loader.dll`，均位于安装根目录。

### 方式二：从源码运行 / 构建

要求：Node.js ≥ 18；若要打桌面安装包，还需 Rust 工具链 + WebView2 开发环境。

```bash
cd desktop
npm install
npm run dev          # 开发模式（tsx 热重载），浏览器开 http://localhost:8080
# 或生产模式（仅 Node 服务，无桌面外壳）：
npm run build && npm start
```

**配置（可选，均有默认值）**

服务读取环境变量；若仓库根存在 `.env` 会自动加载，不存在则用下表默认值，**无需手动创建**。

| 变量                | 默认          | 说明           |
| ----------------- | ----------- | ------------ |
| `PORT`            | 8080        | 监听端口         |
| `HOST`            | 0.0.0.0     | 绑定地址         |
| `DEVICE_NAME`     | My Computer | 电脑显示名        |
| `SHARE_CODE`      | 空           | 可选共享码        |
| `MAX_FILE_SIZE`   | 2GB         | 单文件上限        |
| `DATA_DIR`        | ./data      | 数据存储目录（received/outbox/settings.json/history.json） |
| `DISCOVER_LAN_IP` | true        | 是否探测并展示局域网 IP |

**打包桌面安装版**

```bash
cd desktop
npm run tauri:build
```

产物位于 `desktop/src-tauri/target/release/bundle/`（`nsis/` 下为 `setup.exe`，另含便携压缩包）。该命令会先执行 `scripts/bundle-server.mjs`：编译 Node 服务、将其与 `node_modules` 复制进 `resources/server`、并把 `node.exe` 作为 sidecar 一并打包。

---

## 📱 安卓端

### 系统分享入口

`AndroidManifest.xml` 已将本应用注册为分享目标（支持 `image/*`、`video/*`、`audio/*`、`application/*`、`text/*` 的单文件与多文件分享）。在任意 App 中点「分享」→ 选「局域网互传」，文件会暂存；打开 App 并连上电脑后，暂存项自动进入发送列表，无需重新选文件。

### 构建

要求：Android Studio + Android SDK（compileSdk 34，minSdk 24），或命令行 Gradle。

```bash
cd android
./gradlew assembleDebug     # 输出 app/build/outputs/apk/debug/local-sharing_<YY.M.D>.apk（版本=构建日期 semver 形式，如 26.9.7）
# 正式发布包（需先配置签名，见下）：
./gradlew assembleRelease   # 输出 local-sharing_<YY.M.D>.apk（debug 与 release 同名，不再带 -release 后缀）
```

或用 Android Studio：`File / Open` 选择 `android/` → 等待 Gradle 同步 → `Build / Make Project` 或 `Run` 到设备。

**发布签名（release 包）**

`assembleRelease` 默认使用 Android 自动生成的 debug 签名；若要正式分发/上架，需用自己的 keystore：

```bash
# 用本机 JDK 的 keytool 生成发布密钥（妥善保管 .jks 与两个密码）
keytool -genkeypair -v -keystore local-sharing-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias localsharing
```

随后在 `android/app/build.gradle.kts` 的 `android { signingConfigs { ... } }` 中接入 `storeFile / storePassword / keyAlias / keyPassword`，并在 `buildTypes.release` 设置 `signingConfig = signingConfigs.getByName("release")`。**密钥等同于应用身份，丢失将无法给老用户推更新，请离线备份、勿入库。**

**关于「诊断日志」与构建类型**

诊断日志依赖 `debuggable=true` 才能读取系统级 logcat，因此：

- `assembleDebug`（当前分发方式）：诊断日志**可用**，崩溃栈可抓取。
- `assembleRelease`（`isDebuggable=false`）：系统禁止 App 读日志，诊断日志将**静默失效**（点开可能显示「暂无日志」）。

> 当前以 debug 包分发给信任用户，诊断日志正常工作，无需改动。

### 权限说明

- `INTERNET`：局域网通信（HTTP，已开启 `usesCleartextTraffic`）。
- `CAMERA`：扫码连接（运行时申请）。
- 接收文件写入 App 私有/用户指定目录（SAF），无需存储权限。

---

## 📖 使用指南

### 桌面端操作

- 启动后窗口展示**二维码**与**局域网地址**（`http://<LAN_IP>:8080`）。
- 左侧/下方显示**已连接设备**列表与**收到的文件**。
- 「**发送到设备**」面板：选择本机文件/文件夹 → 选目标设备 → 发送；进度实时显示。
- 设置共享码：通过 `.env` 的 `SHARE_CODE` 启用（开放 WiFi 建议开启）。

### 手机端操作

1. **连接**：打开 App → 「扫码连接」扫电脑二维码，或「手动连接」输入 `IP:端口`（及共享码）。
2. **发送（手机 → 电脑）**：连上后点「选择文件 / 选择文件夹」→ 「发送到电脑」；也可从系统分享入口直接分享进来。
3. **接收（电脑 → 手机）**：电脑端推送后，手机端「收到的文件」出现条目，点「保存」存入指定目录；开启「自动保存」则自动存入。
4. **保存位置**：在「保存位置」处用系统目录选择器指定保存目录（持久记忆），或保持默认的应用 Downloads。
5. **诊断日志**：顶栏「诊断日志」按钮可查看/复制/分享崩溃日志（详见下节）。

---

## 🔌 API 契约

| 方法   | 路径                      | 说明                                       |
| ---- | ----------------------- | ---------------------------------------- |
| GET  | `/health`               | 健康检查                                     |
| GET  | `/api/info`             | 连接信息（名称/IP/端口/是否需共享码）                    |
| GET  | `/api/qr`               | 连接地址的二维码 PNG                             |
| POST | `/api/devices`          | 设备注册，返回 `deviceId / token / wsUrl`         |
| GET  | `/api/devices`          | 已注册设备列表（电脑仪表盘用）                          |
| GET  | `/api/received`         | 收到的文件列表                                  |
| POST | `/api/upload`           | 手机→电脑上传（需 `x-device-id` + `x-token` 头）       |
| POST | `/api/transfer/out`     | 电脑→手机推送（multipart + `deviceId`）            |
| GET  | `/api/transfer/:id`     | 手机拉取推送文件                                 |
| POST | `/api/transfer/:id/ack` | 手机确认接收完成                                 |

WebSocket：`/ws?token=<deviceToken>`（设备）或 `/ws?role=pc`（电脑仪表盘实时更新）。

---

## 🔒 安全说明

- 仅限局域网内通信，默认不加密（HTTP）。请勿在不可信的公共 WiFi 上长期开启。
- 建议在不信任环境设置 `SHARE_CODE`，陌生设备将无法注册。
- 传输文件默认存储在电脑端 `data/` 与手机 App 私有/指定目录，不会外泄到第三方。

---

## 🐞 诊断日志（移动端，调试包专属）

**是什么**：移动端内置崩溃日志采集（`util/CrashLogCollector` + `ui/LogScreen`）。

- 应用启动后在后台持续采集系统 logcat（环形缓冲上限 2MB），并挂全局未捕获异常处理器抓取 Java 层崩溃；native 崩溃（signal 11）的 debuggerd 调用栈也能被后台 logcat 进程捕获。
- 顶栏「诊断日志」可查看最近日志，支持**复制 / 分享**（直接发微信、邮件）。
- 上次运行若崩溃，首页会弹红条「点此导出诊断日志」主动提醒报障。

**前提**：必须运行 `assembleDebug`（`debuggable=true`）。`assembleRelease` 下该能力静默失效（见上）。

**隐私提示**：日志采集范围为**全系统** logcat，分享时可能夹带其他 App 的日志/敏感信息，建议发送前确认内容。

---

## ❓ 常见问题

- **桌面端启动报 `WebView2Loader.dll` 缺失 / 提示缺少 WebView2 运行时？**
  安装包内置引导器通常会在首次启动时自动下载 WebView2；若环境离线，请手动安装「Microsoft Edge WebView2 运行时」（独立安装包）。便携版需保证 `WebView2Loader.dll` 与 `local-sharing-desktop.exe` 同级。

- **手机连不上电脑？**
  确认手机与电脑在**同一局域网/网段**；电脑防火墙放行 `8080` 端口；若电脑端设了共享码，手机端需输入一致。可用手机浏览器先访问 `http://<电脑IP>:8080` 验证服务可达。

- **诊断日志点开是空的？**
  多半是装了 `release` 包（不可调试，读不到 logcat）。请使用 `assembleDebug` 包。

- **想改默认端口/电脑名/共享码？**
  桌面端通过环境变量配置（见「配置」表）；便携版可在 `server/` 同级放 `.env`。

---

## 🗺 路线图

**已实现**：双向文件/文件夹传输、扫码直连、共享码、实时进度、电脑端桌面外壳（Tauri 安装包 + 便携版）、移动端系统分享入口、接收端自定义保存目录、传输活动历史、移动端诊断日志。

**待完善**：端到端加密（DTLS / 预共享密钥）、跨平台桌面端（macOS / Linux）、设备间直连优化。

---

## 📄 License

MIT
