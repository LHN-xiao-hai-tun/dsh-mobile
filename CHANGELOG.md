# 变更日志

本项目所有重要变更都记录在此文件。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

## [1.3.5] - 2026-09-23

> 稳定性：在**没有可用 WebView** 的设备上，从「启动即崩」改为「给一张说得清的提示页」。

### 修复

- **设备上没有可用的 WebView 时，App 启动即崩** —— 用户只看到一个系统框「DSH Mobile 已停止」。
  - **复现（真跑 · AOSP android-26 模拟器）**：`WebViewFactory$MissingWebViewPackageException:
    Failed to load WebView provider: No WebView installed`；崩点在 `MainActivity.java:93`
    （`new WebView(this)`），栈是 `View.<init> → WebView.setOverScrollMode → ensureProviderCreated`
    —— 异常从 Activity 构造期冒到框架。
  - **修法**：构造 WebView **之前**先用 `WebView.getCurrentWebViewPackage()`（API 26+ 的静态探针，
    本身**不构造** WebView）判一次；不可用就渲染一张**提示页**并 `return`。
    构造处再加一层 `try/catch (Throwable)` 兜底 —— 探针与真因万一不一致时同样落到提示页。
    提示页只用框架自带控件，**不碰任何 WebView API**（否则在这一页上又崩一次，等于白做）。
    另补 `null` 守卫：`onResume` / `onKeyDown` / `reallyLoad` 在 `web == null` 时不再解引用。
  - **提示页**：标题 + 说明（本 App 只是外壳，页面要靠系统 WebView 渲染）+ 三条出路
    + 「去安装 / 启用 WebView」按钮 + 脚注。
  - **按钮的降级链**：① 若设备**装了** WebView 提供者（`com.google.android.webview` /
    `com.android.webview` / `com.android.chrome`）→ 进系统「应用详情」页让用户启用；
    ② 否则去应用商店；③ 再退到浏览器；④ 三条都不通 → **明说**，而不是什么都不发生。
    > ⚠️ ① 这一条是实测逼出来的：原先直接丢给浏览器，结果 AOSP 镜像里 `https://play.google.com/…`
    > 会解析到 `org.chromium.webview_shell`，**而它自己也要 WebView** ⇒ 它崩了、被强杀，
    > 用户看到的是「点一下 App 消失、又蹦出一个崩溃的 App」。系统设置的应用详情页任何机型都有，稳妥得多。

### 验证（真跑 · 2026-09-23）

| 场景 | 设备 | 结果 |
|---|---|---|
| **修前复现** | AOSP android-26（`default` 镜像 · 无可用 WebView） | ❌ 启动即崩（栈见上） |
| **修后** | 同上 | ✅ 提示页正常显示（5 个文本节点）· `Displayed +473ms` · 无 FATAL / ANR |
| 是哪一道拦下的 | 同上 | ✅ 日志 `未发现可用的 WebView 提供者，改显示提示页` ⇒ 是**探针**拦的，不是靠 `try/catch` |
| 按钮 | 同上 | ✅ 落到 `com.android.settings/.applications.InstalledAppDetails`（`package:com.android.webview`） |
| 返回键 | 同上 | ✅ 单次出 toast「再按一次退出」· 连按两次退出到 Launcher |
| 旋转横屏 | 同上 | ✅ 内容完整（标题 + 按钮都在）· 0 崩溃 |
| **正常路径回归** | `google_apis` android-26（`com.android.chrome` 作 WebView 提供者） | ✅ **不弹提示页**（探针不误报）· 正常走到设置页 · 配好测试地址后 **WebView 真渲染**（页面独有标记命中）· 0 崩溃 |

> ⚠️ **未验的两条分支**（如实记）：① 按钮降级链的第 ④ 步（toast「没有可用的应用商店或浏览器」）
> 需要「无 WebView **且**无商店 **且**无浏览器」的设备，现有镜像走不到；
> ② 构造处的 `try/catch` 兜底只在探针与真因不一致时才会触发，本轮没复现到该情形。

### 变更

- 版本号 `1.3.4` → `1.3.5`（`versionCode` 16 → 17）
- `values/colors.xml` 新增 5 条颜色（提示页配色，沿用「颜色单一色源」口径）

### 兼容

- 存储格式、权限、签名**均未变** ⇒ **覆盖安装即可**，App 内地址与已信任证书都不丢
- **有可用 WebView 的设备上行为完全不变** —— 探针只读一个静态属性，正常路径一行没动（已回归）

## [1.3.4] - 2026-09-23

> 排障能力：**导出一份脱敏好的诊断日志**（默认不上传 · 导出前脱敏 · 零新权限）。

### 新增

- **「导出日志」（排障用）** —— 设置 → 安全 → 导出日志。三条口径：
  - **默认不上传**：App 至今**没有任何自动上报**，本功能也只是「本机生成 → 交给系统分享面板」，去向由用户决定；
  - **导出前脱敏**：地址里的 `token` / `PIN`、URL 上的敏感参数一律替换为 `<已脱敏>`；
    **快捷指令模板内容不写入**（只记条数，因为它可能含私人内容）；IP 与端口保留（排障必需，属内网信息）；
  - **零新权限**：用 `FileProvider`（`exported=false` + 只暴露 `cacheDir/exports/`）把文件**只读**授权给用户当次选中的那个 App
    —— 本 App 至今**不需要任何运行时权限**，这条没破。
  - 日志内容：版本/设备/配置（已脱敏）+ 本应用相关的 `logcat`（最多 1500 行，含崩溃栈）。

### 修复

- **脱敏误伤**：`logcat` 里 `ActivityRecord{… token=…}` 这类 **Android 内部字段**（值是
  `android.os.BinderProxy@…` 对象引用）曾与"密钥"撞名被打码 ⇒ 判据从"长度"改为**字符集**
  （真实密钥只由 `[A-Za-z0-9_-+/=]` 组成；含 `.`/`@` 即判为非密钥，不脱敏）。真机日志回归：误伤 0。

### 变更

- 版本号 `1.3.3` → `1.3.4`（`versionCode` 15 → 16）

## [1.3.3] - 2026-09-22

> 修「扫描局域网上的 DSH」扫不到电脑 —— 真机实测 0 台，根因是端口清单少了一个。

### 修复

- **「扫描局域网上的 DSH」扫不到电脑**（真机实测：0 台）。真因：扫描的候选端口清单写死
  `{3081, 3080}`，而电脑上 **DSH Pocket 实际监听 `3082`**（`dsh-pocket/settings.json` 的
  `proxyPort`），3081 上根本没有服务、3080 只绑 loopback ⇒ **必然扫不到**。
  证据链（2026-09-22 实测）：电脑 `3082` = 200（Pocket 首页标题 `DSH Pocket · 正在进入`）、
  `3081` = 连不上、`3080` = 平板侧超时；而 mDNS 快路径在真机上也没命中（电脑侧广播正常，
  SRV/TXT/A 均正确回网卡地址 —— 平板侧未收到，疑 AP 组播隔离）。
  - 修法：候选端口补 `3082` 并置于最前（`LanScan.PORTS = {3082, 3081, 3080}`）；
    Pocket 首页标题含 `dsh pocket` ⇒ 命中 `classify()` 的**强特征**，判为**高置信**（无需人工确认）。
  - 设置页提示文案同步：`3081` → `3082`（并注明扫描会依次试 3082/3081/3080）。
  - 代价：子网扫描从 254×2 = 508 次探测变 254×3 = 762 次（48 线程 / 350ms 超时，仍约 5~6 秒上限；
    命中即提前收摊）。
  - ⚠️ 治标提醒：这台电脑的 WLAN IP **会漂**（当天实测 `192.168.10.9 → 192.168.10.41`）——
    漂了以后 App 里**写死的地址**会静默失联（且页面可能显示 WebView 缓存，看着"正常"）。
    根治要么在路由器做 **DHCP 保留**，要么让"扫描"可用（本次修的就是后者）。

### 构建

- **构建可复现**：同一份源码、连续两次构建现在产出**完全相同的 APK**（SHA-256 逐字节一致）
  —— 「下载后用哈希核对是不是作者发的那一份」这件事**从此成立**（此前每次构建都不一样，等于没有校验能力）。
  - **真因**：AGP 默认会往 APK 的「APK 签名块」里再塞一个 **SDK 依赖信息块**（id `0x504b4453`），
    其内容是 `Tink HybridEncrypt(Deflate(依赖清单))` —— **每次构建都用新的临时密钥**，密文天然随机。
    于是同一份源码两次构建**体积一模一样（2,581,841 B）、SHA-256 却必然不同**。
    （两处常见的猜测实测都**不是**原因：ZIP 条目时间戳**已被 AGP 固定**成常量；v1 签名**没有启用**；
    签名密钥是 **RSA**，v2 签名本身是确定性的。）
  - **修法**：`app/build.gradle` 加 `dependenciesInfo { includeInApk = false }`（AGP 官方开关）。
    AAB 保持开启 —— AAB 由 Play 重签、不面向用户做哈希核验，依赖元数据留着无害。
  - **代价**：APK 内不再带 Google Play 的 SDK 依赖元数据。该块属**可选**元数据，**不影响安装、运行与签名**；
    本项目经 GitHub Releases 分发 APK，不依赖它。
  - **验收**：连续三次 `clean assembleRelease` → SHA-256 **完全一致**（体积 2,577,745 B，
    比修前少 4096 B —— 签名块按 4096 对齐，去掉一整块正好缩一个对齐单位）；
    装机到荣耀 CHG-W60 后 WebView 正常渲染、无 `FATAL EXCEPTION` / 无 ANR。
    > ⚠️ 这里**故意不写死哈希值**：revision 会进包（见下条），把某个哈希写进本文件本身就会改变
    > HEAD、从而让那个值失效。**具体哈希与验收过程**记在工作区 `02_产物\dsh-mobile\` 的落地记录里。
  - ⚠️ **比对哈希的前提是同一个 commit**：APK 里的 `META-INF/version-control-info.textproto`
    会记下**构建时的 git revision**，所以不同 commit 构建出的包哈希必然不同（这是特性，便于溯源）。
    自检脚本见工作区 `scripts\repro-check.ps1`。
- **CI 补 lint / 单元测试闸 / 可复现闸**（`.github/workflows/build.yml`）：
  `lintRelease`（报告 always 上传）· `testReleaseUnitTest`（当前无测试源，NO-SOURCE 通过，先把闸门装好）·
  **可复现闸**（连续两次 `clean assembleRelease` 比 SHA-256，不一致直接 fail）+ 产出 `SHA256SUMS.txt` 工件。
  - ⚠️ **签名口径**：CI 无 keystore ⇒ 构建的是**未签名** release APK，其哈希与**发布件必然不同**；
    正式发布的哈希仍由本地 `scripts\release.ps1` 计算并写进 Release Notes。CI 的闸只保证「自己跟自己可复现」。
  - CI 上**镜像只在本地生效**（`settings.gradle` 按 `CI` 环境变量判断）：实测 runner 上阿里云镜像缺
    `commons-io → commons-parent:58 → org.apache:apache:29` 父链 ⇒ `lintVitalAnalyzeRelease` 解析失败。

### 修复

- **lint 报 2 个 `NewApi` error**：`values/` 与 `values-night/` 的 `android:forceDarkAllowed` 需 API 29（minSdk 26）
  → 标 `tools:targetApi="29"`。**行为不变**（API 29 以下系统本就忽略该属性）。
  为什么以前没暴露：`assembleRelease` 里的 `lintVital` **只拦 Fatal**，而 `NewApi` 是 **Error** ——
  是这次补的完整 `lintRelease` 才把它照出来。

## [1.3.2] - 2026-09-22

> 两个「**回归时才暴露出来**」的问题：快捷指令填不进去、浮层位置偶发跳。

### 修复

- **快捷指令「点指令 → 填入输入框」不生效**（v1.3.1 逐条回归时发现）。两处根因叠加：
  - **选错元素**：旧脚本取「最后一个可见候选」（`c[c.length-1]`），而 DSH 页面上
    textarea / contenteditable **有好几个** —— 取最后一个基本是错的。
    现按「**面积越大 + 位置越靠屏幕下方**」打分，挑最像聊天输入框的那个。
  - **写了但被框架丢掉**：旧脚本直接 `el.value = …` / `el.textContent = …`，
    而 React / Vue / ProseMirror 这类**受控组件不认**直接赋值（它们的值来自自己的状态）。
    现改为**先试 `document.execCommand('insertText')`** —— 编辑器认这条路，会走它自己的
    beforeinput/input 流程；不行再回退到「**原型上的原生 setter** + input/change 事件」
    （原生 setter 才绕得过 React 对 `value` 的拦截）。
  - 附带两处：`contenteditable` 的匹配从写死的 `="true"` 放宽为「**存在即算**」
    （`plaintext-only` 会漏）；最后**回读校验**，DOM 真的变了才报成功 ——
    否则给新提示「**页面没接受填入，已复制到剪贴板**」（旧版会把它错报成"没找到输入框"）。
- **浮动按钮位置偶发「跳到底部」**。用临时探针实测：正常路径
  `top=1066 → fy=0.6263 → 还原 top=1066` **是精确往返的**，问题在**尺寸未稳定时也算比例** ——
  `Prefs.setFab` 会把比例**静默 clamp 成 [0,1]**，一旦某一帧 `rootHeight()` / `fabHeight()`
  还是 0 或残留旧值，比例就会 >1 被 clamp 成 **1.0** ⇒ 下次启动「拖在中间却跳到最下面」。
  - `saveFabPosition()`：尺寸没测好、或比例不在 [0,1] ⇒ **这一帧不记**（宁可不记，也不记个错的）
  - `restoreFabWhenMeasured()`：判据**补上高度**（原来只看宽度 —— 高度那一帧为 0 时
    `maxFabTop()` 退化成 0，还原出来会贴顶）

### 变更

- 版本号 `1.3.1` → `1.3.2`（`versionCode` 13 → 14）

### 兼容

- 存储格式、权限、签名**均未变** ⇒ **覆盖安装即可**，App 内地址与已信任证书都不丢

## [1.3.1] - 2026-09-22

> 地址切换流程的两个修复。**同一条根因、两种表现** —— 都在 `MainActivity.onResume()`：
> 它只判断「有没有地址」，不判断「地址**变没变**」。两处都在 v1.3.0 的真机上复现过。

### 修复

- **在设置页改完地址后，主界面不重载**（表现为「改完地址毫无反应，要杀掉 App 重开才生效」）。
  根因：旧判据是 `web.getUrl() == null` —— WebView 已经加载过页面时它**永远不成立**，
  于是新地址根本没被下发。
  现改为比「**你配置的地址**」与「**上次已下发的地址**」：不一致就重载。
  ⚠️ 刻意**不**拿 `web.getUrl()` 去比 —— DSH 登录跳转 / 补尾斜杠都会让它与配置值不同，
  用它当判据会变成"每次回到前台都重载一次"。
- **清空地址后按返回键，被反复弹回设置页**（用户被关在设置页里出不去）。
  根因：`onResume()` 里只要地址为空就**无条件** `startActivity(SettingsActivity)`。
  现改为「**引导只在启动时做一次**」：从设置页返回、地址仍为空时，就停在主界面
  （空白页 + 右下角 ⚙），不再弹回设置页。
  （附带好处：首次启动且无地址时，不会再出现 onCreate 与 onResume 各开一次设置页。）

### 变更

- 版本号 `1.3.0` → `1.3.1`（`versionCode` 12 → 13）

### 兼容

- 存储格式、权限、签名**均未变** ⇒ **覆盖安装即可**，App 内地址与已信任证书都不丢

## [1.3.0] - 2026-09-22

> 安全加固批 —— **首次动到连接与存储层**。仍**不需要动 DSH 一行代码**。
> 原则与文档批一致：**只写做到了的事**；能不改的行为就不改（例如媒体自动播放刻意保留）。

### 新增

- **证书 TOFU（首次信任）** —— `onReceivedSslError` 从「无覆写、走系统默认」升级为显式策略：
  - **首次**见到某主机的证书 → 亮出 **SHA-256 指纹 + 颁发对象**，让你与服务端逐位核对，
    确认后才记住并放行（自签证书的 DSH 从此不用再每次手工放行）
  - 指纹**一致** → 静默放行（正常复连）
  - 指纹**变了** → **强警告**（列出旧/新指纹），默认取消，必须**再点一次**才替换
  - **拿不到指纹 → 一律取消**；**任何情况下都不会无条件 `proceed()`**
- **明文连接安全策略**（`NetPolicy`）：连接前分级判定 ——
  - 私有网段 + `http://`（本 App 的典型用法）→ 告知是明文，**首次确认一次**，之后不再打扰
  - **公网 + `http://` → 默认拦下**，要你明确接受风险才继续
  - `https://` → 不打扰
- **`network_security_config.xml`**：明文策略有单一真源；**信任锚只列系统 CA**
  —— 不接受用户手动安装的 CA（挡「装个根证书就能 MITM 你的 https」）
- **本机敏感项加密**（`SecretStore`）：服务器地址 / 连接历史 / 快捷指令模板改用
  **AES-256-GCM + Android Keystore** 落盘（`enc1:` 前缀）
- 设置页新增「**安全**」小节：本机加密说明 + 已信任证书列表（可逐条清除 / 一键重置）

### 变更

- **WebView 加固**：关掉文件访问（`allowFileAccess` / `allowContentAccess` /
  file-URL 跨源）、`savePassword`、地理定位、JS 自动开窗、多窗口
- **混合内容**：`MIXED_CONTENT_ALWAYS_ALLOW` → **`NEVER_ALLOW`**
  （本 App 的页本身就是明文 http，该开关对它不生效 ⇒ 不影响局域网用法，只挡住 https 页里的 http 子资源）
- **网页权限请求收敛**：原来**无条件放行全部资源**；现在只对**你配置的那个主机**放行
  **麦克风 / 摄像头**，其余一律拒绝
- **清单**：`allowBackup=false`（解密密钥在 Keystore、不随云备份走，避免"还原后解不开"）；
  去掉 `usesCleartextTraffic`（有 XML 后它会被忽略，留着是**两处真源**）
- 版本号 `1.2.7` → `1.3.0`（`versionCode` 11 → 12）

### 刻意**不**改（避免功能回归）

- `mediaPlaybackRequiresUserGesture` **保留 false** —— DSH 侧有语音播报 / TTS，
  收紧成 true 会变成"必须手动点一下才响"
- **未**拦截"网页内跳到外部站点"的行为 —— 那是 WebView 内部导航，
  改它要动主流程，而收益有限；留作后续（需要真机逐条回归）

### 隐私（口径更新）

- 原文「地址等以**明文**存在 App 私有存储」**已不成立**：敏感项现在加密存放
  （键在 Android Keystore，不出设备）。详见 `PRIVACY.md`
- 「不采集、不上传、无广告、无统计、无第三方 SDK」**不变**；
  **未引入任何新依赖**（没有用 `androidx.security:security-crypto` —— 它已被 Google 废弃，
  且会把 Tink 拖进来，与本项目「约 3 MB」的定位冲突）

## [1.2.7] - 2026-09-22

> 局域网发现的**速度**补强 —— mDNS/NSD 快路径上线（配套 DSH 端广播插件）。

### 新增

- **mDNS/NSD 快路径**：发现 `_dsh._tcp.` 服务（端口由 **SRV 记录**给出），
  手机**无需扫 254 个地址**即可即时发现电脑上的 DSH —— 命中通常 **≤1 秒**。
  - 服务类型由 DSH 端**自报** → 命中按**高置信（strong）**呈现，强于 HTTP 特征猜测
  - 支持 **TXT 记录** `ver=<DSH 版本>` / `pin=1`（表示该入口需要 8 位局域网 PIN）
  - 持 `MulticastLock`（部分机型不持锁收不到组播）；拿不到锁时**继续**，不致命
- 扫描按钮文案先显示「正在发现…」，命中后切回计数态。

### 变更

- **两条路径并集去重**：mDNS 与子网扫描共用一个结果出口，同一 `host:port` **只报一次**。
- **mDNS 命中后提前收摊子网扫描**（用户感知"秒出"），命中后留 **1.2 秒**宽限收其余实例。

### 兼容与降级

- **子网扫描保留为兜底**：mDNS 会被 AP 隔离 / 路由器 IGMP snooping 挡掉
  → **绝不单点**（拿不到 `NsdManager`、组播不可用、发现启动失败 → 一律**静默降级**，不崩不阻塞）。
- **不新增敏感权限**：只加 `CHANGE_WIFI_MULTICAST_STATE`（组播锁用）；
  **不申请定位/近场权限**（那类用于读 SSID，本 App 不需要）。
- 地址存储、历史记录、断线重连、v1.2.6 的 HTTP 特征分级**均未改动** → 覆盖安装即可，地址不丢。


## [1.2.6] - 2026-09-22

> 局域网扫描的**准确度**补强 —— 纯 App 侧，仍不动 DSH 一行代码。
> 起因：2026-09-22 误报率实测（`建议\DSH文档\评价\dsh-mobile_HTTP误报率实测与v1.2.6清单_2026-09-22.md`）。

### 修复

- **HTTP 特征误报**：旧判据 `body 含 dsh | harness | deepseek` 三词**全是泛词** →
  实测 `https://www.deepseek.com/` 会被误认成 DSH（真阳性 2/2 正确，但误报面过大）。
  现改为**分级判定**：
  - **强特征**（`dsh pocket` / `dsh web authentication` / `deepseek harness` / `dsh 本地构建` / 标题含 DSH、harness）→ 高置信，直接当 DSH
  - **泛词**（单独出现 `deepseek` / `harness` / `dsh`）→ 候选，交人工确认

### 变更

- **扫描结果分级展示**：高置信结果排在前面；泛词候选统一带「（需确认）」后缀，
  避免"点进去发现不是 DSH"。唯一高置信时仍**直接填入**（保留 v1.2.5 行为）。
- 扫描中的进度文案区分「已找到 N 个 DSH」。

### 兼容

- 组装方式、签名、地址存储、历史记录格式**均未变** → 覆盖安装即可，App 内地址不丢。


## [1.2.5] - 2026-09-22

> 两个「稳定性 / 可用性」补强 —— 均**纯 App 侧**，**不需要动 DSH 一行代码**。

### 新增

- **断线自动重连**：网络波动导致主框架加载失败时，不再只弹一次 toast ——
  顶部出现**离线横幅**（可点即立即重试），并**按指数退避自动重连**
  （1s → 2s → 4s → 8s → 15s → 30s → 60s 封顶）；另注册默认网络回调，
  **网络一恢复就立刻重试**（不必等退避到点）。重连用 `loadUrl` 而非重建 WebView
  ⇒ **Cookie / LocalStorage / 登录态全部保留**。
  ⚠️ 只对**主框架**失败生效 —— 子资源（图片/接口）失败不该把整页判死。
- **局域网扫描发现**：设置页新增「**扫描局域网上的 DSH**」——
  主动探测本机子网 `192.168.x.1-254` 的 **3081（dsh-pocket 局域网）/ 3080（Web UI）**，
  找到 1 个直接填入地址栏、多个弹列表选、0 个给排查提示。**免去「自己查电脑 IP」**。

### 为什么**不是** mDNS

外部建议里提到用 mDNS/NSD 自动发现 —— 但 **mDNS 需要服务端广播 `_dsh._tcp.local`**，
而 **DSH 侧目前不广播任何服务**（要改 DSH 源码或另写插件才行）。
纯 App 侧做 mDNS 会**扫不到任何东西**。
⇒ 改为**主动扫描**：同样一键发现，且**不需要动 DSH**。将来 DSH 若支持广播，可平滑切回 mDNS。

### 隐私（口径不变）

扫描只做 **TCP 连接探测**（连上即断，**不发送任何应用层数据**），
结果只留在内存、**不上传不落盘**。沿用原有声明：无广告、无统计、无第三方 SDK。

### 验证

- `:app:assembleDebug` / `:app:assembleRelease` 通过
- 代码内中文字面量 **0 处**（新增文案全部进 `strings.xml`）
- 键一致性：**51 个 `R.string` 引用 ↔ 定义全部命中，悬空 0**
- 清理：删掉因改造而变成死键的 `err_connect`

## [1.2.4] - 2026-09-21

> 纯重构 —— **无功能变化**。

### 变更

- **用户可见文字全部搬入 `strings.xml`** —— 4 个 Java 文件里的 **53 处中文字面量 → 0 处**
  （`AboutActivity` 21 · `QuickCommands` 15 · `SettingsActivity` 14 · `Prefs` 3）。
  `strings.xml` 从 7 个 key 扩到 **48 个**，按「设置页 / 关于页 / 快捷指令 / 默认模板」分节。
- `Prefs.DEFAULT_TEMPLATES`（`static final` 常量，静态初始化取不到资源）改为
  `defaultTemplates(Context)` 方法；`templates()` / `resetTemplates()` 同步调整。
- 新增代码约定：**代码里不再写任何用户可见中文**，一律引用 `R.string`。

### 为什么这么做

1. **除乱码风险根** —— 构建层早已用 `options.encoding = 'UTF-8'` 兜底（1.2.2 引入，
   针对曾出现的「璁剧疆」乱码标题）；这次把文字**集中到单一文件** = 第二道防线。
2. **为多语言留口** —— 将来加 `values-en/strings.xml` 即可，无需改动 Java。

### 验证

- `:app:assembleDebug` 通过（资源链接 + Java 编译 + 打包）
- 残留扫描：5 个 Java 文件中文字面量 **0 命中**
- 键一致性：**45 个 `R.string` 引用 ↔ 48 个定义 → 悬空 0**
- **APK 产物级核验**（解包读回资源表）：`&lt;/&gt;` 转义（`http://<电脑IP>:3081`）、
  需保留的前导空格、`%1$s` 占位符 —— 均正确

## [1.2.3] - 2026-09-21

> ⚠️ 本版修的是 **1.2.2 上线的浮层缺陷**（真机复现后定位）。

### 修复

- **浮动按钮落在左上角（每次启动都复现）** —— 两处根因**叠加**：
  1. `root.post(this::restoreFabPosition)` 在**布局测量完成前**就执行 → 容器与浮层尺寸都还是 `0` → 「比例 × 最大值」恒为 `0` → 被压到左上角（真机实测 bounds `[0,165][100,265]`）。改为 `OnGlobalLayoutListener`：**仅当容器与浮层都真正测量完成**才还原，成功后立即移除监听；尺寸取值加**回退链**（`getWidth()` → `getMeasuredWidth()` → `DisplayMetrics`），**绝不让 0 参与比例乘法**。
  2. `root.addView(fabStack)` **未显式给 `LayoutParams`**，而 `FrameLayout` 的默认值是 `MATCH_PARENT / MATCH_PARENT` → 小栈**铺满全屏**，`getWidth()` 等于屏宽 → `maxFabLeft()` 恒为 `0`。改为显式 `WRAP_CONTENT` + 出厂 `Gravity.BOTTOM | Gravity.END` + 边距（即使还原逻辑出问题也不会掉到左上角）。
- **拖拽完全不动** —— 即上面第 2 条：`maxFabLeft() == 0` 时左右拖拽无位移。
- **与 DSH 官方控件重叠区域的触摸被 WebView 抢走**（真机实测：点在浮层内却打开了 DSH 自己的侧边栏）—— 浮层加 `setElevation()` 提升层级，并在每次 `onPageFinished` 后 `bringToFront()`；`fabStack` 与两个按钮均 `setClickable(true)`。
- **旋转屏幕后同样要等测量完成**再回拉（`onConfigurationChanged` 加守卫，避免尺寸为 0 时 clamp 把浮层推走）。

### 变更

- 按钮边长 `40dp → 44dp`（更好按）；文字色 `0x99FFFFFF → 0xCCFFFFFF`、底色 `0x33000000 → 0x4D000000`（更易看清）

## [1.2.2] - 2026-09-21

> ⚠️ **该版浮层有缺陷**：实测落在左上角、拖拽不可用、与官方控件重叠时触摸被 WebView 抢走 —— 已在 **1.2.3** 修复。

### 修复
- **浮动按钮与 DSH 官方 Web UI 的控件重叠**：`⚙`（设置）与 `⌘`（快捷指令）原先都钉在右上角（`Gravity.TOP|END`）浮在 WebView 之上，压住了 DSH 自己的模式选择 / ⋯ 菜单 / 省钱开关等右上角控件。现改为：
  - 两个按钮合成**一个竖向小栈**，**默认停在右下角**
  - **可拖拽**：按住拖动，松手**吸附最近的左右边缘**（不会拖出屏幕，也不会进状态栏）
  - **位置会被记住**：存的是「可移动范围的比例」，旋转屏幕 / 换密度后依然合理，下次启动自动恢复
  - 仅在按钮自身区域内接管触摸；位移**未超过 `touchSlop` 仍按点击处理**，不抢 WebView 手势

### 变更
- 桌面 / 最近任务里的应用名由 `DSH` 改为 **`DSH Mobile`**（避免与他人的 DSH 混淆）
- 构建配置补 `options.encoding = 'UTF-8'`：Windows 中文环境下 javac 默认按 GBK 读源码，中文会乱码（v1.2.0 修掉的 `璁剧疆` 正是这个根因，现从构建层堵住）
- 版本号 `1.2.1` → `1.2.2`（`versionCode` 5 → 6）

## [1.2.1] - 2026-09-21

### 变更
- **改用正式签名证书**：v1.2.0 及更早版本的 release APK 使用的是 Android **调试证书**（`CN=Android Debug`），本版起改用专为本项目生成的正式证书（RSA 2048 · 有效期 30 年）
- 版本号 `1.2.0` → `1.2.1`（`versionCode` 4 → 5）
- 构建配置：`signingConfigs.release` 从仓库外的 `keystore.properties` 读取签名信息；**该文件不入库**，他人 clone 后仍可正常 `assembleDebug`

### ⚠️ 升级须知
- 由于**签名证书变更**，本版**无法覆盖安装**旧版：需**先卸载旧版，再安装本版**
- 卸载会清除 App 内保存的**服务器地址**与**连接历史**，重装后重新填写即可

## [1.2.0] - 2026-09-21

### 新增
- **启动页**：改用 Android 官方 SplashScreen API（深色底 + 官方 DSH 图标），不再是一片空白
- **「关于」页**（设置页底部进入）：版本号（运行时读取）、MIT 许可说明、致谢 DeepSeek Harness、GitHub 仓库入口、「检查更新」按钮
- **连接历史**：自动记录最近 **5** 个连接过的地址（去重），设置页里点一下即可填入
- **快捷指令面板**（主界面 ⌘ 按钮）：3 条**可编辑**的常用提示，点一下填入 DSH 输入框
- **GitHub Actions 自动构建**：push / PR 时自动 `assembleDebug` 并上传 APK 产物
- Issue / PR 模板、`CONTRIBUTING.md`

### 修复
- **设置页在「最近任务」里的标题是乱码**（`璁剧疆`，应为「设置」）—— manifest 的 label 编码错误，已改为字符串资源
- **gradle wrapper 残缺**：仓库只有 `gradlew.bat`，缺 `gradlew` 与 `gradle-wrapper.jar`，导致 README 里写的 `./gradlew assembleDebug` 与 CI 都无法运行 —— 已补全

### 变更
- 深色模式**显式化**：新增 `values-night/themes.xml`；关闭 Android 10+ 的「强制暗色」二次反转（`forceDarkAllowed=false`），避免 WebView 内容色偏
- 颜色收敛为单一色源（`@color/app_bg`），主题里不再写死色值
- 应用名与各页面标题统一走 `strings.xml`
- 版本号 `1.1.1` → `1.2.0`（`versionCode` 3 → 4）

### 说明
- 快捷指令**只填入、绝不代替你发送**；输入框已有内容时**追加**而非覆盖；找不到输入框时退化为复制到剪贴板
- 连接历史与指令模板**只存本机 SharedPreferences**，不采集、不上传

## [1.1.1] - 2026-09-21

### 变更
- 图标换成**官方 DSH logo**（从 DSH 的 `favicon.svg` 提取路径并转为 Android vector）
- 移除自绘占位图标

## [1.1] - 2026-09-21

### 新增
- **极简 WebView 容器**（约 3 MB），**不含 DSH 本体**
- **全屏**显示，无浏览器地址栏
- **返回键**处理：网页后退 / 双击退出
- **麦克风·摄像头**自动授权（语音输入可用）
- **登录态持久**（Cookie 自动保存）
- 右上角 ⚙ 随时切换服务器地址；**未填地址时首次启动自动进入设置页**
- 连不上时给出明确提示，**不静默白屏**

### 变更
- 包名改为中性 **`app.dshmobile`**
- **不预置任何服务器地址**（由用户自行填写）

## [1.1] 之前的内部版本

v1.1 之前的内部构建使用旧包名，未在本仓库发布 Release，故不在此记录。

[未发布]: https://github.com/LHN-xiao-hai-tun/dsh-mobile/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/LHN-xiao-hai-tun/dsh-mobile/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/LHN-xiao-hai-tun/dsh-mobile/compare/v1.1...v1.1.1
[1.1]: https://github.com/LHN-xiao-hai-tun/dsh-mobile/releases/tag/v1.1
