# local-sharing · 统一 UI 设计规范

> 局域网文件互传工具（电脑端 Web + 安卓端）统一设计规范与屏幕重设计方案。
> 目标：**清爽、轻量、有科技感**，两端视觉一致，形成同一品牌（连接 / 互传主题）。

> ⚠️ **硬性约束（来自产品评审）**：PC→手机方向**无法显示真实百分比进度**（手机经 HTTP 拉取、靠 ack 通知，服务端不知下载字节）。因此「发送中」状态**只能用离散状态药丸**（`发送中` / `已完成` / `失败`），**不得设计成百分比进度条**。传输记录卡片须支持**一条传输含多条文件**（A 方案修复 ws.ts 仅转发首文件的问题）。

---

## 1. 美学定位

- **一句话**：轻盈通透的科技蓝界面——以「连接」为核心，用克制的渐变与柔和的卡片阴影营造“近在咫尺”的传输体验。
- **三个关键词**：`清爽 Clean` · `轻量 Lightweight` · `通透 Airy`
- **品牌内核**：`⇄` 双向箭头 = 对等、即时、双向流动。所有视觉语言都应暗示“两端在对话”。

---

## 2. 品牌系统

### 2.1 Logo 概念（两端共用）
- 图形：双向箭头 `⇄`，置于圆角方形（圆角 12px）内。
- 填充：主色渐变 `linear-gradient(135deg, #3b6cff → #5b7cff)`。
- 桌面（CSS / SVG）：

```html
<!-- 品牌标记，推荐内联 SVG 以保证清晰 -->
<svg class="brand-mark" viewBox="0 0 40 40" width="40" height="40" aria-hidden="true">
  <defs>
    <linearGradient id="ls-g" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#3b6cff"/>
      <stop offset="1" stop-color="#5b7cff"/>
    </linearGradient>
  </defs>
  <rect width="40" height="40" rx="12" fill="url(#ls-g)"/>
  <path d="M12 16h13l-3-3 1.4-1.4L27.8 17l-4.4 4.4L22 20l3-3H12v-1zm16 8H15l3 3-1.4 1.4L12.2 23l4.4-4.4L18 20l-3 3h16v1z"
        fill="#fff"/>
</svg>
```

- 安卓（Compose）：复用同一 `⇄` 路径作为 `ImageVector`，或在 `TopAppBar` / 连接页用 `Icon(painter = painterResource(R.drawable.ic_brand))`。建议将 SVG 导出为 `ic_brand.xml` 矢量资源，保证两端像素一致。

### 2.2 品牌语言（一致性约束）
| 维度 | 规范 |
|------|------|
| 主色 | 两端均使用 `#3b6cff`（安卓 `0xFF3B6CFF`） |
| 圆角语言 | lg 16 / md 12 / sm 8 / full（胶囊）；大容器可达 20 / 24 |
| 阴影 | 仅卡片用柔和投影，列表项用极浅投影或描边，避免“重盒子” |
| 空状态语气 | 友好、鼓励式，第一人称可选：“还没有连接设备，扫描二维码开始吧 👋” |
| 图标风格 | 线性 1.5px / 填充 24px 网格，优先 Material Symbols / Material Icons |
| 微文案 | 动词开头、短句；成功用“已…”，进行中用“…中”，失败用“未能…”，不甩锅给用户 |
| 状态表达 | 进行中用**离散药丸 + 旋转指示**，不用百分比条（见 §3.6） |

---

## 3. 设计 Token

> 所有 token 为两端唯一事实来源。桌面以 CSS 变量落地，安卓以 `Color` / `Dp` / `TextUnit` 落地，hex 与数值一一对应。

### 3.1 色彩 Token

**品牌主色（蓝）**
| Token | Hex | 用途 | 安卓 |
|-------|-----|------|------|
| `primary` | `#3b6cff` | 主按钮、链接、激活态、品牌 | `0xFF3B6CFF` |
| `primary-hover` | `#2a55d8` | 主按钮 hover / active | `0xFF2A55D8` |
| `primary-100` | `#eaf0ff` | 软填充（pill 底、hover 底、拖拽区） | `0xFFEAF0FF` |
| `primary-200` | `#d7e0ff` | 软描边（次级按钮边框、聚焦环） | `0xFFD7E0FF` |

**语义色**
| Token | Hex | 用途 | 安卓 |
|-------|-----|------|------|
| `success` | `#1faa59` | 在线、成功、保存完成 | `0xFF1FAA59` |
| `success-soft` | `#e6f7ee` | 成功 pill 底 | `0xFFE6F7EE` |
| `warning` | `#e08a00` | 连接中、重连中、等待 | `0xFFE08A00` |
| `warning-soft` | `#fff2dc` | 警告 pill 底 | `0xFFFFF2DC` |
| `danger` | `#e5484d` | 错误、删除、断开、发送失败 | `0xFFE5484D` |
| `danger-soft` | `#fdeaea` | 危险 pill 底 / 错误条 | `0xFFFDEAEA` |
| `danger-hover` | `#c93b40` | 危险按钮 hover | `0xFFC93B40` |

**中性灰阶（冷调，微微带蓝，呼应品牌）**
| Token | Hex | 用途 |
|-------|-----|------|
| `bg` | `#f4f6fb` | 页面背景 |
| `surface` | `#ffffff` | 卡片 / 浮层 |
| `surface-2` | `#f0f2f7` | 次级填充（输入底、列表项底、进度轨道） |
| `line` | `#e6e9f2` | 描边 / 分隔线 |
| `line-strong` | `#d8dde8` | hover 描边 / 聚焦底 |
| `disabled` | `#c2c8d6` | 禁用态填充 / 文字 |
| `hint` | `#9aa2b5` | 占位符 |
| `muted` | `#6b7488` | 次要文字（meta、说明） |
| `ink-2` | `#4a5266` | 次级标题 |
| `ink` | `#1c2333` | 正文 / 主文字 |
| `ink-strong` | `#141a2b` | 大标题 / 强调 |

**深色模式（两端可选启用，保持同一套语义）**
| Token | Light | Dark |
|-------|-------|------|
| `bg` | `#f4f6fb` | `#0b1020` |
| `surface` | `#ffffff` | `#151b2e` |
| `surface-2` | `#f0f2f7` | `#1e2438` |
| `line` | `#e6e9f2` | `#2c3650` |
| `muted` | `#6b7488` | `#a6aec2` |
| `ink` | `#1c2333` | `#e6e9f2` |
| `primary` | `#3b6cff` | `#7c9bff` |
| `success` | `#1faa59` | `#4cc07f` |
| `danger` | `#e5484d` | `#ff6b70` |

### 3.2 圆角（Radius）
| Token | 值 | 用途 |
|-------|-----|------|
| `radius-sm` | `8px` | 输入框、小 chip、列表项、二维码内框 |
| `radius-md` | `12px` | 按钮、卡片内元素、二维码容器 |
| `radius-lg` | `16px` | 卡片、主容器 |
| `radius-xl` | `20px` | Hero 区、大浮层、对话框 |
| `radius-full` | `999px` | 状态点、徽章 pill、头像、FAB |

### 3.3 阴影 / 高度（Elevation）
**桌面（box-shadow）**
| Token | 值 | 用途 |
|-------|-----|------|
| `shadow-xs` | `0 1px 2px rgba(20,26,43,.04)` | 列表项、chip |
| `shadow-sm` | `0 2px 8px rgba(20,26,43,.06)` | 悬浮列表项、下拉 |
| `shadow-md` | `0 6px 24px rgba(28,35,51,.08)` | 卡片（默认） |
| `shadow-lg` | `0 16px 40px rgba(28,35,51,.12)` | Hero 抬升、拖拽区、对话框 |
| `shadow-primary` | `0 8px 24px rgba(59,108,255,.30)` | 主按钮 hover、品牌 CTA 光晕 |

**安卓（M3 Elevation，dp）**
| 组件 | Level | 说明 |
|------|-------|------|
| Card（装饰性） | 0 + 描边 | 用 `surfaceVariant` 描边代替投影，更轻盈 |
| Card（强调/可点） | 1（2dp） | 默认可点击卡片 |
| FAB / 扫码 CTA | 3（6dp） | 浮起强调 |
| TopAppBar（滚动后） | 2（4dp） | 滚动时加投影收边 |
| Snackbar | 6（6dp） | 常驻底部浮层 |
| Dialog / BottomSheet | 3–6 | 模态抬升 |

### 3.4 间距尺度（4px 基准，禁止随意数值）
```
--space-1: 4px   --space-2: 8px   --space-3: 12px  --space-4: 16px
--space-5: 20px  --space-6: 24px  --space-8: 32px  --space-10: 40px
--space-12: 48px --space-16: 64px
```
**常用组合**：卡片内边距 `space-6`(24)；卡片网格间距 `space-5`(20)；标题下边距 `space-4`(16)；列表项内边距 `space-3 space-4`(12/16)；Hero 区上下 `space-10/12`。

### 3.5 字体与字号层级
**字体栈（中文优先）**
```
font-family: -apple-system, BlinkMacSystemFont, "PingFang SC",
             "Microsoft YaHei", "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
mono: "SF Mono", "JetBrains Mono", "Cascadia Code", Consolas, monospace;  /* 连接地址 / 共享码 */
```
字重：400 常规 / 500 中等 / 600 半粗 / 700 粗（避免 800+）。

**桌面字号阶梯（px / weight / line-height）**
| Token | 大号 | 行高 | 用途 |
|-------|------|------|------|
| `text-display` | 28 / 700 | 1.25 | Hero 主标题（如本机名） |
| `text-h1` | 20 / 650 | 1.3 | 页面标题 |
| `text-h2` | 16 / 600 | 1.4 | 卡片标题 |
| `text-h3` | 14 / 600 | 1.4 | 分区小标题 |
| `text-body` | 14 / 400 | 1.6 | 正文 |
| `text-sm` | 13 / 400 | 1.5 | 说明、meta |
| `text-caption` | 12 / 400 | 1.4 | 次要标注 |
| `text-overline` | 11 / 600 | 1.4 | 大写 + ls .08em，分区标签 |
| `text-code` | 13 / 500 | mono | 连接地址、共享码 |

**安卓字号（M3 Typography，见 §5.1）**：以 `bodyMedium` 14sp 为主，`titleMedium` 16sp 为卡片标题，`labelLarge` 14sp 为按钮，`headlineSmall` 24sp 为连接页大标题。

### 3.6 状态模型（离散状态药丸）⚠️
受「PC→手机无真实进度」约束，传输状态一律用**离散药丸**，不用百分比：

| 状态 | 桌面 pill | 安卓表现 | 语义 |
|------|-----------|----------|------|
| `发送中` / `连接中` | `.pill.warn` + 旋转小圈 / `dot.connecting` | 不确定态 `CircularProgressIndicator` 或 warning 药丸 | 进行中，未知百分比 |
| `已完成` | `.pill.on` | `tertiary` 药丸 / 对勾 | 成功 |
| `失败` | `.pill.fail` | `error` 药丸 | 失败 |
| `重连中` | `dot.connecting` + 文案“重连中…” | `ConnState.Reconnecting` 警告药丸 | 断开后重连 |

> **例外**：安卓端「保存到本机」的进度是**真实**的（本机写入字节可知），**可保留 `LinearProgressIndicator` 百分比**；仅 PC→手机「发送中」受此约束。桌面端「收到的文件」由手机经 HTTP 上传到 PC，PC 可知字节，但为与「发送」保持同一套离散药丸语言、降低认知负担，统一用离散药丸表达接收态（如需可额外显示大小）。

---

## 4. 桌面端重设计

### 4.1 整体布局
```
┌──────────────────────────────────────────────────────────────┐
│ TopBar [⇄] local-sharing      [● 实时已连接 / 重连中…]   ⚙(P2) │
├──────────────────────────────────────────────────────────────┤
│ HERO（品牌渐变）  [二维码]  本机名 / 连接地址 [📋 复制]          │
├──────────────────────────────────────────────────────────────┤
│  ┌───────────────┐   ┌───────────────┐                        │
│  │ 已连接设备     │   │ 发送到设备     │                        │
│  │ 📱手机 ●      │   │ [选设备▾]      │                        │
│  │ 📟平板 ●      │   │ ⬚拖拽区(高亮)  │                        │
│  └───────────────┘   │ [文件][文件夹] │                        │
│  ┌───────────────┐   │ [ 发送 → ]     │                        │
│  │ 传输记录       │   └───────────────┘                        │
│  │ ↗ a.png 发送中 │                                               │
│  │ ↘ doc 已完成   │                                               │
│  └───────────────┘                                               │
└──────────────────────────────────────────────────────────────┘
```
- 容器最大宽 `1120px`，居中；左右留白 `space-6`。
- Hero 与卡片区之间留 `space-8` 呼吸感。
- 响应式：≤ 720px 单列，Hero 二维码居中、地址可横向滚动。
- 卡片顺序：连接(Hero 全宽) → 设备 / 发送（一行两列）→ 传输记录。

### 4.2 全局 CSS 变量（`styles.css` 顶部）
```css
:root {
  /* 色彩 */
  --primary: #3b6cff;
  --primary-hover: #2a55d8;
  --primary-100: #eaf0ff;
  --primary-200: #d7e0ff;
  --success: #1faa59;  --success-soft: #e6f7ee;
  --warning: #e08a00;  --warning-soft: #fff2dc;
  --danger: #e5484d;   --danger-soft: #fdeaea;  --danger-hover: #c93b40;

  --bg: #f4f6fb;  --surface: #ffffff;  --surface-2: #f0f2f7;
  --line: #e6e9f2;  --line-strong: #d8dde8;
  --disabled: #c2c8d6;  --hint: #9aa2b5;  --muted: #6b7488;
  --ink-2: #4a5266;  --ink: #1c2333;  --ink-strong: #141a2b;

  /* 圆角 */
  --r-sm: 8px;  --r-md: 12px;  --r-lg: 16px;  --r-xl: 20px;  --r-full: 999px;

  /* 阴影 */
  --sh-xs: 0 1px 2px rgba(20,26,43,.04);
  --sh-sm: 0 2px 8px rgba(20,26,43,.06);
  --sh-md: 0 6px 24px rgba(28,35,51,.08);
  --sh-lg: 0 16px 40px rgba(28,35,51,.12);
  --sh-primary: 0 8px 24px rgba(59,108,255,.30);

  /* 间距（4 基准） */
  --s1:4px; --s2:8px; --s3:12px; --s4:16px; --s5:20px; --s6:24px;
  --s8:32px; --s10:40px; --s12:48px; --s16:64px;

  /* 字体 */
  --font: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei",
          "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  --mono: "SF Mono", "JetBrains Mono", "Cascadia Code", Consolas, monospace;
}
```

### 4.3 关键组件样式与片段

**按钮（主 / 次 / 危险 / 幽灵）**
```css
.btn { font: 500 14px/1 var(--font); border-radius: var(--r-md);
       padding: 0 var(--s4); height: 44px; cursor: pointer;
       display: inline-flex; align-items: center; justify-content: center; gap: var(--s2);
       transition: background .18s ease, box-shadow .18s ease, transform .12s ease, border-color .18s ease;
       border: 1px solid transparent; }
.btn:active { transform: translateY(1px); }

.btn.primary { background: var(--primary); color: #fff; box-shadow: var(--sh-primary); }
.btn.primary:hover { background: var(--primary-hover); }
.btn.primary:disabled { background: var(--disabled); box-shadow: none; cursor: not-allowed; }

.btn.secondary { background: var(--surface); color: var(--ink); border-color: var(--line-strong); }
.btn.secondary:hover { background: var(--surface-2); border-color: var(--primary-200); }

.btn.danger { background: var(--danger); color: #fff; }
.btn.danger:hover { background: var(--danger-hover); }

.btn.ghost { background: transparent; color: var(--primary); }
.btn.ghost:hover { background: var(--primary-100); }

.btn.sm { height: 36px; padding: 0 var(--s3); font-size: 13px; }
```

**卡片**
```css
.card { background: var(--surface); border: 1px solid var(--line);
        border-radius: var(--r-lg); padding: var(--s6); box-shadow: var(--sh-md); }
.card__title { font: 600 16px/1.4 var(--font); margin: 0 0 var(--s4);
               display: flex; align-items: center; gap: var(--s2); }
.card__title .badge { margin-left: auto; }
```

**状态点 / 徽章**
```css
.dot { width: 8px; height: 8px; border-radius: var(--r-full); display: inline-block; }
.dot.on { background: var(--success); box-shadow: 0 0 0 3px var(--success-soft); }
.dot.off { background: var(--disabled); }
.dot.connecting { background: var(--warning); animation: pulse 1.2s ease-in-out infinite; }
@keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.35} }

.badge { font: 600 12px/1 var(--font); background: var(--primary); color:#fff;
         border-radius: var(--r-full); padding: 4px 10px; }
.pill { font: 500 12px/1 var(--font); border-radius: var(--r-full); padding: 4px 10px; display: inline-flex; align-items: center; }
.pill.on  { background: var(--success-soft); color: var(--success); }
.pill.off { background: var(--surface-2);  color: var(--muted); }
.pill.warn{ background: var(--warning-soft); color: var(--warning); }
.pill.fail{ background: var(--danger-soft);  color: var(--danger); }
.pill.folder { background: var(--warning-soft); color: var(--warning); }

/* 发送中：warning 药丸 + 旋转小圈，不显示百分比 */
.pill.warn .spin { width: 10px; height: 10px; margin-right: 5px;
                   border: 2px solid currentColor; border-top-color: transparent;
                   border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
```

**列表项（设备 / 通用）**
```css
.list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--s2); }
.item { display: flex; align-items: center; gap: var(--s3); padding: var(--s3) var(--s4);
        border: 1px solid var(--line); border-radius: var(--r-md); font: 400 14px/1.4 var(--font);
        background: var(--surface); transition: background .15s ease, border-color .15s ease, box-shadow .15s ease; }
.item:hover { background: var(--surface-2); border-color: var(--line-strong); box-shadow: var(--sh-xs); }
.item .ico { width: 36px; height: 36px; border-radius: var(--r-md); display: grid; place-items: center;
             background: var(--primary-100); color: var(--primary); flex: none; font-size: 18px; }
.item .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item .meta { color: var(--muted); font-size: 12px; }
```

**设备类型图标（phone / tablet / pc）**  ← 优先级 C
```css
/* d.type → JS 渲染 class + 图标 + 标签（手机 / 平板 / 电脑） */
.dev-phone  .ico { background: var(--primary-100); color: var(--primary); }
.dev-tablet .ico { background: var(--success-soft); color: var(--success); }
.dev-pc     .ico { background: #eef0ff;          color: #5b54d8; }
```

**复制按钮（连接地址旁）**  ← 优先级 B
```css
.copy { display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 var(--s3);
        border-radius: var(--r-md); border: 1px solid var(--primary-200); background: #fff;
        color: var(--primary); font: 500 13px/1 var(--font); cursor: pointer; transition: background .15s ease; }
.copy:hover { background: var(--primary-100); }
.hero .copy { background: #fff; color: var(--primary); }   /* Hero 内白底版本 */
```

**拖拽区（发送面板）**  ← 优先级 B
```css
.drop { border: 1.5px dashed var(--line-strong); border-radius: var(--r-lg);
        padding: var(--s6); text-align: center; color: var(--muted);
        transition: border-color .15s ease, background .15s ease, transform .12s ease; cursor: pointer; }
.drop:hover { border-color: var(--primary-200); background: var(--primary-100); }
.drop.over { border-color: var(--primary); background: var(--primary-100); transform: scale(1.01); }
```

**传输记录卡片（替换「收到的文件」）**  ← 优先级 A（受 §3.6 约束）
> 每条记录含：方向箭头（↗ 电脑→手机 / ↘ 手机→电脑）、文件名、设备名、大小、时间、状态药丸；received 项右侧带 ✕ 删除；卡片头右侧「清空」按钮（二次确认）。一条传输可含多条文件（ws 修复后），列表按文件分行。
```css
.records { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--s2); }
.rec { display: flex; align-items: center; gap: var(--s3); padding: var(--s3) var(--s4);
       border: 1px solid var(--line); border-radius: var(--r-md); background: var(--surface);
       transition: background .15s ease, border-color .15s ease; }
.rec:hover { background: var(--surface-2); }
.rec .dir { width: 28px; height: 28px; border-radius: var(--r-full); display: grid; place-items: center;
            font-size: 14px; font-weight: 700; flex: none; }
.rec .dir.up   { background: var(--primary-100); color: var(--primary); }   /* ↗ 电脑→手机 */
.rec .dir.down { background: var(--success-soft); color: var(--success); }  /* ↘ 手机→电脑 */
.rec .name { flex: 1; min-width: 80px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font: 500 14px/1.4 var(--font); }
.rec .dev  { color: var(--muted); font-size: 12px; }
.rec .size { color: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.rec .time { color: var(--hint);  font-size: 11px; }
.rec .rm { border: none; background: transparent; color: var(--muted); cursor: pointer;
           width: 28px; height: 28px; border-radius: var(--r-full); }
.rec .rm:hover { background: var(--danger-soft); color: var(--danger); }

/* 清空（二次确认）：默认幽灵文字按钮，点击后置确认态 */
.btn.confirm { color: var(--danger); border-color: var(--danger-soft); background: var(--danger-soft); }
```

**进度条（用于真实进度场景，如本地上传/保存；PC→手机发送不启用）**
```css
.progress { height: 6px; border-radius: var(--r-full); background: var(--surface-2); overflow: hidden; }
.progress > i { display: block; height: 100%; border-radius: var(--r-full);
                background: linear-gradient(90deg, var(--primary), #5b7cff);
                transition: width .25s ease; }
.progress.indeterminate > i { width: 40%; animation: indet 1.1s ease-in-out infinite; }
@keyframes indet { 0%{margin-left:-40%} 100%{margin-left:100%} }
```

**空状态**
```css
.empty { display: flex; flex-direction: column; align-items: center; text-align: center;
         gap: var(--s2); padding: var(--s10) var(--s4); color: var(--muted); }
.empty .ring { width: 56px; height: 56px; border-radius: var(--r-full);
               display: grid; place-items: center; background: var(--primary-100);
               color: var(--primary); font-size: 24px; margin-bottom: var(--s2); }
.empty .t { font: 600 14px/1.4 var(--font); color: var(--ink-2); }
.empty .s { font: 400 13px/1.5 var(--font); max-width: 260px; }
```

**Toast**
```css
.toast { position: fixed; bottom: var(--s6); left: 50%; transform: translateX(-50%) translateY(16px);
         background: var(--ink); color: #fff; padding: var(--s3) var(--s5); border-radius: var(--r-md);
         font: 500 14px/1.4 var(--font); box-shadow: var(--sh-lg); opacity: 0; pointer-events: none;
         display: flex; align-items: center; gap: var(--s2); transition: opacity .25s ease, transform .25s ease; z-index: 60; }
.toast.show { opacity: 1; transform: translateX(-50%) translateY(0); }
.toast.ok::before { content: "✓"; color: #7ee2a8; }
.toast.err::before { content: "!"; color: #ff9aa0; }
```

**二维码容器**
```css
.qr { width: 180px; height: 180px; border: 1px solid var(--line); border-radius: var(--r-md);
      padding: var(--s3); background: #fff; display: grid; place-items: center; }
.qr img { width: 100%; height: 100%; image-rendering: pixelated; }
```

**Hero 区（品牌渐变 + 大二维码 + 一键复制）**
```css
.hero { background: linear-gradient(135deg, #3b6cff 0%, #5b7cff 100%);
        border-radius: var(--r-xl); padding: var(--s10) var(--s8);
        display: flex; align-items: center; gap: var(--s8); color: #fff; box-shadow: var(--sh-lg); }
.hero .qr { background: #fff; }
.hero .addr { font: 500 15px/1.5 var(--mono); background: rgba(255,255,255,.16);
              border: 1px solid rgba(255,255,255,.28); color: #fff;
              padding: var(--s3) var(--s4); border-radius: var(--r-md); }
.hero .copy { background: #fff; color: var(--primary); }
.hero .copy:hover { background: #eef2ff; }
```

**（P2）设置弹窗**  ← 优先级 F
```css
.modal-mask { position: fixed; inset: 0; background: rgba(20,26,43,.45);
              display: grid; place-items: center; z-index: 80; padding: var(--s4);
              opacity: 0; pointer-events: none; transition: opacity .2s ease; }
.modal-mask.show { opacity: 1; pointer-events: auto; }
.modal { width: min(420px, 100%); background: var(--surface); border-radius: var(--r-xl);
         padding: var(--s6); box-shadow: var(--sh-lg); transform: translateY(8px); transition: transform .2s ease; }
.modal-mask.show .modal { transform: translateY(0); }
.modal h3 { margin: 0 0 var(--s4); font: 600 16px/1.4 var(--font); }
.modal .field { margin-bottom: var(--s4); }
.modal .actions { display: flex; justify-content: flex-end; gap: var(--s2); margin-top: var(--s2); }
```

### 4.4 桌面页面结构建议（HTML 骨架）
```html
<header class="topbar">
  <div class="brand"><span class="brand-mark">⇄</span>
    <div><h1>local-sharing</h1><p class="sub">局域网互传 · 电脑端</p></div></div>
  <div class="conn"><span class="dot on"></span> 实时已连接</div>
  <button class="btn ghost sm" id="settingsBtn" title="设置" style="margin-left:auto">⚙ 设置</button>  <!-- (P2) -->
</header>

<section class="hero">
  <div class="qr"><img id="qr" alt="二维码"></div>
  <div class="hero-info">
    <div class="overline">本机名称</div>
    <div class="display" id="devName">—</div>
    <div class="overline" style="margin-top:16px">连接方式</div>
    <div class="addr" id="connectUrl">—</div>
    <button class="copy" id="copyBtn">📋 复制地址</button>
    <p class="hint" style="color:rgba(255,255,255,.85)">手机扫码，或输入地址即可连接</p>
  </div>
</section>

<main class="grid">
  <!-- 优先级 C：设备列表按 d.type 渲染图标/标签 -->
  <section class="card"><h2 class="card__title">已连接设备 <span class="badge" id="devCount">0</span></h2>
    <!-- JS 渲染：<li class="item dev-phone"><span class="ico">📱</span><span class="name">小米手机</span><span class="pill on">在线</span></li> -->
    <ul class="list" id="deviceList"></ul></section>

  <!-- 优先级 B：发送面板 + 拖拽区（dragover/drop 高亮） -->
  <section class="card"><h2 class="card__title">发送到设备</h2>
    <label class="field"><span>选择设备</span><select id="targetDevice"></select></label>
    <div class="drop" id="drop">把文件拖到这里，或
      <label class="btn primary sm">选择文件<input type="file" id="fileInput" multiple hidden></label>
      <label class="btn secondary sm">选择文件夹<input type="file" id="folderInput" webkitdirectory hidden></label>
    </div>
    <ul class="list" id="pickList"></ul>
    <button class="btn primary" id="sendBtn" disabled>发送 →</button>
    <div class="log" id="sendLog"></div></section>

  <!-- 优先级 A：传输记录卡片（替换「收到的文件」） -->
  <section class="card">
    <h2 class="card__title">传输记录 <span class="badge" id="recCount">0</span>
      <button class="btn ghost sm confirm" id="clearRec" style="margin-left:auto">清空</button>
    </h2>
    <ul class="records" id="recordList"></ul>
    <!--
    ===== 传输记录数据契约（GET /api/activity）=====
    items[]:
      id:         string  // in: "rcv_xxx"；out: "tr_xxx"
      direction: "in" | "out"          // in=手机→电脑，out=电脑→手机
      name:      string  // out 方向为 zip 包名
      size:      number  // bytes
      kind:      "file" | "folder"
      deviceName:string // in: fromDeviceName；out: 由 toDeviceId 解析的目标设备名
      status:    string  // in 恒 "completed"；out: "ready"|"completed"|"failed"
      time:      number  // 毫秒；in: receivedAt；out: createdAt
    渲染规则（无需 transferId 分组，两端粒度天然不同）：
      · direction==="in"  → ↘ 图标（.dir.down）；out → ↗ 图标（.dir.up）
      · status 药丸：out "ready"→发送中(.pill.warn+.spin)、"completed"→已完成(.pill.on)、
                     "failed"→失败(.pill.fail，ACK 超时)；in 恒显「已接收」(.pill.on)
      · 仅 direction==="in" 渲染 ✕ 删除 → DELETE /api/received/:id（id=rcv_xxx）；out 行不可删
      · 卡片头「清空」只清 in 记录 → DELETE /api/received
      · 不展开多文件、暂不加 fileCount（out 一行=一个 zip 包；in 一行=一个 ReceivedFile）
    示例单条（JS 逐行渲染）：
    <li class="rec">
      <span class="dir up">↗</span>
      <span class="name">照片.zip</span>
      <span class="dev">Pixel 7</span>
      <span class="size">120.5 KB</span>
      <span class="time">14:03</span>
      <span class="pill warn"><span class="spin"></span>发送中</span>
    </li>
    <li class="rec">
      <span class="dir down">↘</span>
      <span class="name">doc.pdf</span>
      <span class="dev">Pixel 7</span>
      <span class="size">1.2 MB</span>
      <span class="time">14:01</span>
      <span class="pill on">已接收</span>
      <button class="rm" title="删除">✕</button>
    </li>
    -->
  </section>
</main>

<!-- (P2) 设置弹窗 -->
<div class="modal-mask" id="settingsMask">
  <div class="modal">
    <h3>设置</h3>
    <label class="field">设备名 <input id="setName" class="ipt"></label>
    <label class="field">端口 <input id="setPort" class="ipt"></label>
    <label class="field">共享码 <input id="setCode" class="ipt"></label>
    <div class="actions">
      <button class="btn ghost sm" id="setCancel">取消</button>
      <button class="btn primary sm" id="setSave">保存</button>
    </div>
  </div>
</div>
```

### 4.5 桌面深色模式（叠加变量）
```css
@media (prefers-color-scheme: dark) {
  :root {
    --bg:#0b1020; --surface:#151b2e; --surface-2:#1e2438; --line:#2c3650; --line-strong:#3a4660;
    --muted:#a6aec2; --ink:#e6e9f2; --ink-2:#c4ccdd; --ink-strong:#f4f6fb;
    --primary:#7c9bff; --success:#4cc07f; --danger:#ff6b70;
    --sh-md:0 6px 24px rgba(0,0,0,.35); --sh-lg:0 16px 40px rgba(0,0,0,.45);
  }
}
```

---

## 5. 安卓端重设计（Material 3）

### 5.1 主题（`ui/theme/Theme.kt`）
```kotlin
package com.localsharing.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// —— 品牌色（与桌面 hex 完全一致）——
private val Primary      = Color(0xFF3B6CFF)
private val PrimaryHover = Color(0xFF2A55D8)
private val OnPrimary    = Color.White
private val PrimaryC     = Color(0xFFEAF0FF)   // primaryContainer
private val OnPrimaryC   = Color(0xFF0B2A8A)
private val Secondary    = Color(0xFF5B7CFF)
private val Success      = Color(0xFF1FAA59)
private val Warning      = Color(0xFFE08A00)
private val Error        = Color(0xFFE5484D)
private val ErrorC       = Color(0xFFFDEAEA)
private val OnErrorC     = Color(0xFF7A1216)

// —— 中性灰（冷调，与桌面一致）——
private val Bg     = Color(0xFFF4F6FB)
private val Surf   = Color.White
private val Surf2  = Color(0xFFF0F2F7)
private val Line   = Color(0xFFE6E9F2)
private val Muted  = Color(0xFF6B7488)
private val Ink    = Color(0xFF1C2333)

private val Light = lightColorScheme(
    primary = Primary, onPrimary = OnPrimary, primaryContainer = PrimaryC, onPrimaryContainer = OnPrimaryC,
    secondary = Secondary, onSecondary = OnPrimary,
    tertiary = Success, onTertiary = Color.White,          // “保存成功”可用 tertiary
    error = Error, onError = OnPrimary, errorContainer = ErrorC, onErrorContainer = OnErrorC,
    background = Bg, onBackground = Ink,
    surface = Surf, onSurface = Ink,
    surfaceVariant = Surf2, onSurfaceVariant = Muted,
    outline = Line, outlineVariant = Color(0xFFD8DDE8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7C9BFF), onPrimary = Color(0xFF0B1020), primaryContainer = Color(0xFF1E3A8A), onPrimaryContainer = Color(0xFFD7E2FF),
    secondary = Color(0xFF8FA6FF), onSecondary = Color(0xFF0B1020),
    tertiary = Color(0xFF4CC07F), onTertiary = Color(0xFF06231A),
    error = Color(0xFFFF6B70), onError = Color(0xFF3A0608), errorContainer = Color(0xFF4A1417), onErrorContainer = Color(0xFFFFDAD8),
    background = Color(0xFF0B1020), onBackground = Color(0xFFE6E9F2),
    surface = Color(0xFF151B2E), onSurface = Color(0xFFE6E9F2),
    surfaceVariant = Color(0xFF1E2438), onSurfaceVariant = Color(0xFFA6AEC2),
    outline = Color(0xFF2C3650), outlineVariant = Color(0xFF3A4660),
)

// —— 形状：与桌面圆角语言对齐（lg16/md12/sm8/圆形）——
private val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// —— 字体：中文系统字体栈（见 §5.5）——
private val AppType = LocalSharingTypography()

@Composable
fun LocalSharingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes,
        typography = AppType,
        content = content,
    )
}
```

### 5.2 连接页（`ConnectScreen`）
- 顶部：品牌 header（`ic_brand` + 标题 “局域网互传” + 副标题 “local-sharing”）。
- 中部：`Card` 表单（elevation 0 + `surfaceVariant` 描边，更轻）。
  - `OutlinedTextField` IP / 端口 / 共享码，聚焦时描边变 `primary`。
  - 主按钮 “连接”（填充 primary，加载时 `CircularProgressIndicator`）。
  - **扫码 CTA 强调**：用填充 primary + 相机图标 `Icons.Filled.QrCodeScanner`，比“连接”更靠前，引导最高频路径。
- 底部：友好提示（`bodySmall`、muted）。
- 错误：用 `Text` + `error` 色，置于按钮下方；或用 `SnackbarHost` 浮层（见 §5.5）。
- （P2）共享码前置提示：共享码输入框上方加一行说明文案「如电脑端设置了共享码，请在此填写；留空表示无需」（`bodySmall`、muted），降低填写门槛。

```kotlin
Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface,
                                     contentColor = MaterialTheme.colorScheme.onSurface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
) {
    Column(Modifier.padding(20.dp)) {
        OutlinedTextField(
            value = ip, onValueChange = { ip = it },
            label = { Text("电脑 IP 地址") }, placeholder = { Text("例如 192.168.1.20") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("端口") }, modifier = Modifier.weight(.4f), singleLine = true)
            OutlinedTextField(value = code, onValueChange = { code = it },
                label = { Text("共享码（可选）") }, modifier = Modifier.weight(.6f), singleLine = true)
        }
        // (P2) 共享码前置提示
        Spacer(Modifier.height(4.dp))
        Text("如电脑端设置了共享码，请在此填写；留空表示无需。",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth(),
               contentPadding = PaddingValues(14.dp)) {
            Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp)); Text("扫码连接", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.connect(...) }, modifier = Modifier.fillMaxWidth()) {
            Text("手动连接")
        }
    }
}
```

### 5.3 扫码页（`ScannerScreen`）
- 全屏相机（`AndroidView` + `PreviewView`）。
- **取景框装饰**：在相机上方叠加一个圆角矩形描边（4 角括号），中心放二维码；用 `Canvas` 或 4 个 `Box` 角标实现。
- **顶部渐变遮罩**：`Box` 渐变 `primary → transparent`，放标题 “将二维码放入取景框”。
- **底部提示条**：半透明 `surface` 胶囊（`surfaceVariant` + alpha），含提示文字与“取消/手电筒”按钮。
- 成功识别：轻微震动 + 短 `Snackbar` “已识别”，随后返回。

```kotlin
Box(Modifier.fillMaxSize()) {
    AndroidView(factory = { /* …existing camera setup… */ }, modifier = Modifier.fillMaxSize())
    // 顶部渐变
    Box(Modifier.fillMaxWidth().height(120.dp)
        .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, Color.Transparent))))
    Text("将二维码放入取景框", color = Color.White,
         modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
         style = MaterialTheme.typography.titleMedium)
    // 取景框（圆角描边 + 四角括号）
    Box(Modifier.align(Alignment.Center).size(240.dp)
        .border(2.dp, Color.White.copy(alpha = .9f), RoundedCornerShape(16.dp))) {
        // 四角括号可用 4 个 Box(modifier = Modifier.align(...)) 实现
    }
    // 底部提示条
    Surface(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .padding(24.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .9f),
        tonalElevation = 3.dp,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("保持稳定，自动识别", style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}
```

### 5.4 主页（`HomeScreen`）
- `TopAppBar`：标题 “local-sharing”；副标题 “已连接：{pcName}”；右侧 **连接状态药丸 `ConnPill`**（含 `ConnState.Reconnecting`）；`OutlinedButton` “断开”（危险色描边）。滚动时加 elevation。
- **发送卡片**：标题 “发送到电脑”；两个 `Button`（选择文件 / 选择文件夹）；选中项 `Row`（类型图标 + 名称 + 大小 + ✕ 移除）；`Button` “发送到电脑”（加载时 `CircularProgressIndicator` + 百分比——**此为本机上传进度，真实可知，可保留**）；`LinearProgressIndicator(progress = { progress })`。
- **收到的文件**：标题旁加 **自动保存 `Switch`**；每项 `Card`（见 `FileCard`），左侧类型图标，名称 + “文件 · 2.0 MB”，保存中 `LinearProgressIndicator`（真实进度），否则 `Button` “保存”。
- **已保存**：标题旁加 **「清空」按钮（二次确认）**；列表含图标 + 路径（单行省略）。(P2) 顶部加搜索框 + 扩展名图标。
- **空状态**：`EmptyState`（见 §5.5）。
- **错误**：`SnackbarHost` 红色强调。

```kotlin
// 连接状态药丸（顶栏，含重连中）← 优先级 E（需 C 方案在 ConnState 密封类加 Reconnecting）
@Composable
fun ConnPill(state: ConnState) {
    val (bg, fg, label) = when (state) {
        ConnState.Connected    -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, "已连接")
        ConnState.Reconnecting -> Triple(MaterialTheme.colorScheme.errorContainer,   MaterialTheme.colorScheme.onErrorContainer, "重连中…")
        ConnState.Disconnected -> Triple(MaterialTheme.colorScheme.errorContainer,   MaterialTheme.colorScheme.onErrorContainer, "已断开")
        else                   -> Triple(MaterialTheme.colorScheme.surfaceVariant,   MaterialTheme.colorScheme.onSurfaceVariant, "连接中")
    }
    Surface(color = bg, shape = RoundedCornerShape(999.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (state == ConnState.Reconnecting)
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp,
                    color = fg, trackColor = bg)
            else Box(Modifier.size(8.dp).background(fg, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(label, color = fg, style = MaterialTheme.typography.labelMedium)
        }
    }
}
// 用法：TopAppBar actions 放 ConnPill(vm.connState)，并保留 “断开” 按钮

// 收到的文件 标题 + 自动保存 Switch  ← 优先级 D
Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Text("收到的文件", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.weight(1f))
    Text("自动保存", style = MaterialTheme.typography.bodyMedium,
         color = MaterialTheme.colorScheme.onSurfaceVariant)
    Switch(checked = autoSave, onCheckedChange = { vm.setAutoSave(it) })
}

// 已保存 标题 + 清空（二次确认）  ← 优先级 D
var confirmClear by remember { mutableStateOf(false) }
Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Text("已保存", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.weight(1f))
    if (confirmClear) {
        TextButton(onClick = { confirmClear = false }) { Text("取消") }
        Button(onClick = { vm.clearSaved(); confirmClear = false },
               colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Text("确认清空？") }
    } else {
        TextButton(onClick = { confirmClear = true }) { Text("清空") }
    }
}

// (P2) 已保存：搜索框 + 扩展名图标  ← 优先级 G
var q by remember { mutableStateOf("") }
OutlinedTextField(value = q, onValueChange = { q = it },
    modifier = Modifier.fillMaxWidth(), singleLine = true,
    placeholder = { Text("搜索已保存文件") },
    leadingIcon = { Icon(Icons.Filled.Search, null) })
val ext = f.name.substringAfterLast('.', "").uppercase()
val icon = when (ext) {
    "PDF" -> Icons.Filled.PictureAsPdf
    "PNG","JPG","JPEG","GIF","WEBP" -> Icons.Filled.Image
    "MP4","MOV","WEBM" -> Icons.Filled.Movie
    "ZIP","RAR","7Z" -> Icons.Filled.FolderZip
    else -> Icons.Filled.InsertDriveFile
}
// FileCard 传入 icon 覆盖默认 folder/file 图标即可
```

### 5.5 可落地 Compose 片段

**空状态组件**
```kotlin
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape, tonalElevation = 0.dp,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(56.dp),
        ) { Icon(icon, null, Modifier.size(26.dp),
                 tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium,
             color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { Spacer(Modifier.height(16.dp)); it() }
    }
}
// 用法：EmptyState(Icons.Filled.CloudOff, "还没有收到文件", "让电脑端发送，文件会显示在这里")
```

**漂亮的文件卡片**
```kotlin
@Composable
fun FileCard(name: String, meta: String, isFolder: Boolean,
             icon: ImageVector? = null,
             actionLabel: String? = null, onAction: () -> Unit = {},
             progress: Float? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)) {
                Icon(icon ?: if (isFolder) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                     null, Modifier.size(22.dp),
                     tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                     style = MaterialTheme.typography.bodyMedium)
                Text(meta, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (progress != null) {   // 真实进度（本机上传/保存）可用
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
            if (actionLabel != null)
                Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
```

**字体（中文系统栈）**
```kotlin
import androidx.compose.ui.text.font.FontFamily

// 无网络依赖：使用设备默认字体栈（中文优先）
private val AppFont = FontFamily.Default

private fun LocalSharingTypography() = Typography(
    displaySmall = Typography().displaySmall.copy(fontFamily = AppFont),
    headlineSmall = Typography().headlineSmall.copy(fontFamily = AppFont),
    titleMedium = Typography().titleMedium.copy(fontFamily = AppFont),
    bodyMedium = Typography().bodyMedium.copy(fontFamily = AppFont),
    bodySmall = Typography().bodySmall.copy(fontFamily = AppFont),
    labelLarge = Typography().labelLarge.copy(fontFamily = AppFont),
)
```

**错误 Snackbar**
```kotlin
val snackbarHostState = remember { SnackbarHostState() }
Scaffold(
    snackbarHost = {
        SnackbarHost(snackbarHostState) { data ->
            Snackbar(containerColor = MaterialTheme.colorScheme.errorContainer,
                     contentColor = MaterialTheme.colorScheme.onErrorContainer,
                     shape = RoundedCornerShape(12.dp)) { Text(data.visuals.message) }
        }
    },
) { /* … */ }

// 触发：LaunchedEffect(error) { if (error.isNotBlank()) snackbarHostState.showSnackbar(error) }
```

---

## 6. 统一品牌与一致性清单（两端对照）

| 维度 | 桌面 | 安卓 | 一致？ |
|------|------|------|--------|
| 主色 | `#3b6cff` | `0xFF3B6CFF` | ✅ |
| 成功 | `#1faa59` | `0xFF1FAA59` | ✅ |
| 危险 | `#e5484d` | `0xFFE5484D` | ✅ |
| 圆角 | lg16/md12/sm8 | medium16/small12/xs8 | ✅ |
| 字体 | PingFang SC / YaHei | FontFamily.Default（同栈） | ✅ |
| Logo | `⇄` 渐变圆角方 | `ic_brand` 同形 | ✅ |
| 空状态 | 图标环 + 鼓励语 | `EmptyState` 同语气 | ✅ |
| 间距 | 4px 基准 | 4/8/12/16 dp | ✅ |
| 传输状态 | 离散药丸（发送中/已完成/失败） | 同语义药丸 / Snackbar | ✅ |
| 重连态 | `dot.connecting`「重连中…」 | `ConnState.Reconnecting` 药丸 | ✅（E，需 C 方案） |

---

## 7. 微交互 / 体验

| 场景 | 行为 | 时长 / 曲线 |
|------|------|-------------|
| 按钮 hover | 主按钮加 `shadow-primary` 并微抬；次按钮描边转 primary-200 | 180ms ease |
| 按钮 active | `translateY(1px)` | 120ms |
| 聚焦（输入） | 2px `primary-200` 环 + offset 2px | 150ms |
| 列表项 hover | 底变 `surface-2` + 浅投影 | 150ms |
| 拖拽进入 | 拖拽区边框转 primary、底 primary-100、`scale(1.01)` | 120ms |
| 加载（按钮） | `CircularProgressIndicator` 替换文字，不收宽度 | — |
| 状态药丸（发送中） | warning 药丸 + 旋转小圈，**不显示百分比** | spin .8s linear |
| 进度（真实，安卓保存/上传） | 条宽过渡 + 右端显示 `n%` | 250ms ease |
| 进度（不确定） | 条来回滑动 | 1.1s |
| Toast / Snackbar | 底部滑入 + 淡入，成功/错误加左侧色点；自动消失 2.6s | 250ms |
| 状态过渡 | connecting（黄 pulse）→ on（绿 + 光晕淡出）；重连中（黄 pulse） | 400ms |
| 卡片入场 | Hero / 卡片轻微 `fade+rise` | 300ms |
| 清空二次确认 | 按钮置为「确认清空？」danger 态，2.6s 内再点才生效 | 200ms |
| 页面切换（安卓） | `NavHost` 用 `Crossfade` / 共享轴，避免硬切 | 300ms |
| 二维码刷新 | 淡出淡入，避免闪烁 | 200ms |

---

## 8. 实现检查清单（给开发）
> 实现优先级以**产品评审方案 A–H** 为准，字母对照见 §9.2。下方 `[A]`–`[H]` 为本规范内部标注，与评审字母可能不同（如评审 B/D/E 对应本规范 D/E/B/C），以 §9.2 为准。

**桌面（`desktop/src/public/`）— 优先级 A/B/C 先落地**
- [A] 用 §4.3「传输记录卡片」替换「收到的文件」：方向箭头 + 文件名 + 设备名 + 大小 + 时间 + 离散状态药丸；received 项带 ✕ 删除；卡片头「清空」二次确认。
- [A] 服务端正按文件分行渲染（ws 修复后转发全部文件，非仅首文件）。
- [B] 发送面板加 `.drop` 拖拽区（监听 `dragover/dragleave/drop` 高亮）；`#connectUrl` 旁 `.copy` 复制按钮 + Toast `✓`（`navigator.clipboard.writeText`）。
- [C] 设备列表按 `d.type` 渲染图标/标签（手机/平板/电脑），套 `.item` + `.dev-*` 配色。
- [ ] Hero 区（§4.3 `.hero`）保留，连接地址复制沿用。
- [F] (P2) 设置弹窗：设备名 / 端口 / 共享码 三输入（§4.3 `.modal`）。
- ⚠️ 任何「发送中」状态**不得**用百分比进度条，仅离散药丸（见 §3.6）。

**安卓（`android/app/.../ui/`）— 优先级 D/E 先落地**
- [D] HomeScreen「收到的文件」标题旁加自动保存 `Switch`；「已保存」标题旁加「清空」二次确认（§5.4）。
- [E] 顶栏连接状态药丸支持 `ConnState.Reconnecting` → “重连中…”（§5.4 `ConnPill`，需 C 方案在密封类加此态）。
- [ ] 用 §5.1 `Theme.kt` 补全 secondary/tertiary/error/shapes/typography。
- [ ] 主页文件项改用 `FileCard`（支持 `icon` 覆盖）；空状态 `EmptyState`；错误 `Snackbar`。
- [G] (P2) 已保存列表：扩展名图标 + 搜索框（§5.4）。
- [H] (P2) ConnectScreen 共享码前置提示文案（§5.2）。
- ⚠️ PC→手机「发送中」在安卓侧为**本机上传进度**（真实可知），可保留 `LinearProgressIndicator`；仅 PC 仪表盘侧受约束。

**两端通用**
- [ ] 不引入任何需额外构建的 UI 框架（原生 CSS / Compose）。
- [ ] 任何 UI 调整先回写本规范并同步两端。

---

## 9. 修订与覆盖范围（对齐产品评审）

### 9.1 硬性约束（来自产品评审）
- **PC→手机无真实进度**：手机经 HTTP 拉取、靠 ack 通知，服务端不知下载字节 → 「发送中」仅离散药丸（发送中/已完成/失败），禁用百分比进度条。
- **传输记录按文件分行**：A 方案修复 ws.ts 仅转发首文件，记录卡片须支持一条传输含多条文件。

### 9.2 待覆盖 UI 面与优先级（含字母对照）
> 实现优先级以**产品评审方案的 A–H** 为准。下表「评审」为评审方案原始字母，「DESIGN」为本规范 §4/§5/§8 中的字母标注，二者对照以免混淆。

| 评审 | DESIGN | 端 | UI 面 | 对应规范 |
|------|--------|----|-------|----------|
| A | A | PC | 「传输记录」卡片（替换收到的文件）：方向箭头/文件名/设备/大小/时间/状态药丸 + ✕删除 + 清空(确认) | §4.3 传输记录卡片、§4.4 |
| B | D | 安卓 | 「收到的文件」标题旁自动保存 `Switch`；「已保存」标题旁「清空」(确认) | §5.4 |
| C | E | 安卓 | 顶栏连接状态药丸含 `ConnState.Reconnecting`「重连中…」（C 方案**必需**落地项，扩展 `ConnState` 密封类） | §5.4 `ConnPill` |
| D | B | PC | 发送面板拖拽区(高亮) + `#connectUrl` 旁复制按钮 | §4.3 `.drop` / `.copy` |
| E | C | PC | 设备列表按 `d.type`(phone/tablet/pc) 渲染图标/标签 | §4.3 设备类型图标 |
| F | F | PC(P2) | 设置弹窗：设备名/端口/共享码 三输入 | §4.3 设置弹窗 |
| G | G | 安卓(P2) | 已保存列表：扩展名图标 + 搜索框 | §5.4 |
| H | H | 安卓(P2) | ConnectScreen 共享码前置提示文案 | §5.2 |

> 风格延续现有 PC 仪表盘的 Material-ish 浅色卡片 + 安卓端现有 Material 3 主题，未引入新视觉体系。本规范为两端**唯一事实来源**，如需调整先更新本文并同步两端。
