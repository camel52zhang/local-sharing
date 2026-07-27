# 局域网互传工具 · local-sharing

> 一款运行在局域网内的文件/文件夹互传工具：手机/平板扫码或输入电脑 IP 即可连接电脑，  
> 双向互传文件与文件夹，无需公网、无需注册、无需云。

- **中文名**：局域网互传工具
- **英文名**：local-sharing

---

## ✨ 功能特性

1. **手机 → 电脑**：局域网内手机拍的照片、编辑的文件、文件夹，扫码或输入电脑 IP 连接后，  
   直接发送到电脑（自动按设备归类存放）。
2. **电脑 → 手机/平板**：电脑端选择文件或文件夹，推送到已连接的手机/平板，手机端实时收到并保存。
3. **双向文件夹传输**：文件夹在发送端自动打包为 zip，接收端自动解压还原目录结构。
4. **扫码直连**：电脑端界面展示二维码，手机扫码即获取连接地址；也支持手动输入 `IP:端口`。
5. **可选共享码**：开放 WiFi 环境下可设置 6 位以内共享码，防止陌生设备接入。

---

## 🏗 架构

以**电脑为中心**的星型拓扑（手机/平板不做服务端，规避 Android 后台服务与网络限制）：

```
        ┌──────────────┐   HTTP 上传    ┌──────────────────────┐
        │  手机/平板    │ ─────────────▶ │                      │
        │ (Android App) │                │   电脑端服务          │
        │              │ ◀──── 下载 ──── │  (Node.js + Express) │
        │  WebSocket   │   (WS 通知)     │   + Web 仪表盘        │
        └──────────────┘                │                      │
              ▲                          └──────────────────────┘
              │  扫码 / 手动输入 IP:端口
              │
        ┌─────┴────────┐
        │  电脑端二维码 │  http://<LAN_IP>:<PORT>
        └──────────────┘
```

- **实时通道**：手机通过 WebSocket 连接到电脑，用于设备在线状态与“电脑→手机”推送通知。
- **文件方向**：
  - 手机 → 电脑：`POST /api/upload`（multipart），服务端存入 `data/received/<设备>/`。
  - 电脑 → 手机：电脑端先上传到服务端 outbox，服务端经 WS 通知目标设备，手机再 `GET /api/transfer/:id` 拉取。
- **文件夹**：发送端 zip，接收端解压。

---

## 📁 目录结构

```
local-sharing/
├── desktop/            # 电脑端（Node.js + TypeScript）
│   ├── package.json
│   ├── tsconfig.json
│   ├── .env.example
│   └── src/
│       ├── index.ts          # 入口
│       ├── app.ts            # Express 路由
│       ├── ws.ts             # WebSocket 实时层
│       ├── config.ts         # 配置
│       ├── network.ts        # LAN IP 探测
│       ├── types.ts          # 类型
│       ├── services/         # 设备/传输/存储逻辑
│       └── public/           # Web 仪表盘（HTML/CSS/JS）
└── android/            # 安卓端（Kotlin + Jetpack Compose）
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── app/
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/localsharing/app/   # 全部源码
            └── res/                         # 资源与启动图标
```

---

## 💻 电脑端运行

要求：Node.js ≥ 18

```bash
cd desktop
npm install
cp .env.example .env        # 按需修改端口/共享码等
npm run dev                 # 开发模式（tsx 热重载）
# 或生产构建：
npm run build && npm start
```

启动后在浏览器打开 `http://localhost:<PORT>`，界面会显示：

- 本机局域网地址与二维码
- 已连接设备列表
- 收到的文件
- “发送到设备”面板（选择文件/文件夹 → 选设备 → 发送）

手机端扫描二维码，或手动输入该 `IP:端口` 即可连接。

环境变量（`.env`）：

| 变量              | 默认          | 说明     |
| --------------- | ----------- | ------ |
| `PORT`          | 8080        | 监听端口   |
| `HOST`          | 0.0.0.0     | 绑定地址   |
| `DEVICE_NAME`   | My Computer | 电脑显示名  |
| `SHARE_CODE`    | 空           | 可选共享码  |
| `MAX_FILE_SIZE` | 2GB         | 单文件上限  |
| `DATA_DIR`      | ./data      | 数据存储目录 |

---

## 📱 安卓端构建

要求：Android Studio + Android SDK（compileSdk 34，minSdk 24）

1. Android Studio → `File / Open` 选择 `android/` 目录。
2. 等待 Gradle 同步（首次会下载 Gradle 8.9 与依赖）。
3. 配置 `local.properties` 中的 `sdk.dir`（Android Studio 通常自动生成）。
4. `Build / Make Project`，或 `Run` 到设备/模拟器。

权限说明：

- `INTERNET`：局域网通信（HTTP，已开启 `usesCleartextTraffic`）。
- `CAMERA`：扫码连接（运行时申请）。
- 接收文件写入 App 私有下载目录（`getExternalFilesDir`），无需存储权限。

---

## 🔌 API 契约

| 方法   | 路径                      | 说明                                     |
| ---- | ----------------------- | -------------------------------------- |
| GET  | `/health`               | 健康检查                                   |
| GET  | `/api/info`             | 连接信息（名称/IP/端口/是否需共享码）                  |
| GET  | `/api/qr`               | 连接地址的二维码 PNG                           |
| POST | `/api/devices`          | 设备注册，返回 `deviceId / token / wsUrl`     |
| GET  | `/api/devices`          | 已注册设备列表（电脑仪表盘用）                        |
| GET  | `/api/received`         | 收到的文件列表                                |
| POST | `/api/upload`           | 手机→电脑上传（需 `x-device-id` + `x-token` 头） |
| POST | `/api/transfer/out`     | 电脑→手机推送（multipart + `deviceId`）        |
| GET  | `/api/transfer/:id`     | 手机拉取推送文件                               |
| POST | `/api/transfer/:id/ack` | 手机确认接收完成                               |

WebSocket：`/ws?token=<deviceToken>`（设备）或 `/ws?role=pc`（电脑仪表盘实时更新）。

---

## 🔒 安全说明

- 仅限局域网内通信，默认不加密（HTTP）。请勿在不可信的公共 WiFi 上长期开启。
- 建议在不信任环境设置 `SHARE_CODE`，陌生设备将无法注册。
- 传输文件默认存储在电脑端 `data/` 与手机 App 私有目录，不会外泄到第三方。

---

## 🗺 后续计划

- [x] 传输进度实时显示（电脑仪表盘侧）
- [x] 多设备同时推送、批量确认
- [x] 电脑端 Electron 打包为独立桌面应用
- [x] 端到端加密（DTLS / 预共享密钥）
- [x] 断点续传

---

## 📄 License

MIT
