# 安全策略（Security Policy）

本文件说明 **DSH Mobile** 的安全支持范围、漏洞报告渠道与响应预期。

> **先说清定位**：本 App 是一个 **极简 WebView 容器**，只负责在手机 / 平板上访问**你自己部署的** DSH 服务。
> 它**不含 DSH 本体**、**不预置任何服务器地址**、**不采集数据**。
> ⇒ **安全与否，主要取决于你如何暴露 DSH 服务** —— 动手连接前请先读
> [`docs/SECURITY_DEPLOY.md`](docs/SECURITY_DEPLOY.md)。

---

## 一、支持范围

| 版本 | 支持状态 |
|---|---|
| 仓库 `master` 上的最新版本 | ✅ 支持 |
| 最新 Release | ✅ 支持 |
| 更早的版本 | ❌ 不支持 —— 请先升级到最新版再复现 |

> 这是**个人维护**的开源项目：尽力维护，**不承诺商业级 SLA**。

---

## 二、报告漏洞

**请不要在公开 Issue 中披露漏洞细节。**

### 首选：GitHub 私密漏洞报告 ✅

本仓库**已开启** Private vulnerability reporting —— 从下面这个入口提交，**只有维护者可见**：

> 🔒 <https://github.com/LHN-xiao-hai-tun/dsh-mobile/security/advisories/new>

也可以从仓库页面进去：**Security → Advisories → Report a vulnerability**。

### 兜底：用不了该入口时

若你没有 GitHub 账号、或该入口打不开：

1. 新建一个 Issue，标题写 `[安全] 申请私下报告渠道`；
2. **正文不要写任何漏洞细节**，只说明「想报告一个安全问题」；
3. 维护者会在该 Issue 里回复一个私下渠道，之后请在那里提供细节。

报告中请尽量包含：

- **影响版本** —— DSH Mobile 版本（App 内「设置 → 关于 DSH Mobile」可见）+ DSH 版本
- **Android 版本与机型**
- **连接方式** —— 局域网 / Tailscale / ZeroTier / frp / 公网
- 是否启用 HTTPS、是否设置 PIN
- **复现步骤**（越具体越好）
- 影响描述（信息泄露 / 未授权访问 / 代码执行 / 拒绝服务 …）
- PoC 或日志 —— **请先脱敏**：服务器地址、PIN、token 一律用 `192.168.x.x` / `****` 占位

---

## 三、响应预期

个人项目，以下为**目标**（**不是承诺**）：

| 阶段 | 目标 |
|---|---|
| 确认收到 + 初步判断 | **7 天内** |
| 评估影响 + 给出修复计划或说明 | **30 天内** |
| 修复发布 | 在 Release Notes 中说明，并致谢报告者（可要求匿名） |

---

## 四、不在范围内

以下情况**不算**本项目的安全漏洞：

- 你自己把 DSH 暴露在公网、且**未设置 PIN / 密码**
- 你使用 `http://` 明文连接且未采取额外保护（如未使用组网）
- 你的设备已被入侵 / root / 装有恶意软件
- 第三方依赖的上游漏洞 —— 请直接向上游报告（也欢迎抄送本项目）

---

## 五、已知的设计取舍（**不是漏洞，但你应该知道**）

如实列出当前版本（**v1.2.7**）的真实行为，便于你评估风险
（**本节以 v1.2.7 为基准** —— 后续版本若有改动，会同步更新此处）：

| 取舍 | 说明 |
|---|---|
| **允许明文 HTTP** | 清单里 `usesCleartextTraffic="true"` —— 为了支持局域网 `http://<电脑IP>:3081`（`dsh-pocket` 默认如此）。同一网络下的他人**可以窃听**内容。 |
| **混合内容放行** | WebView 设为 `MIXED_CONTENT_ALWAYS_ALLOW`，HTTPS 页面里的 HTTP 子资源不会被拦。 |
| **网页权限请求直接放行** | 网页请求麦克风 / 摄像头时，App 在 WebView 层直接 grant（让语音输入可用）。 |
| **本机数据未加密** | 地址 / 连接历史 / 快捷指令存在 App 私有 `SharedPreferences`，**明文**。详见 [`PRIVACY.md`](PRIVACY.md)。 |
| **保存 Cookie 登录态** | 为免反复登录，Cookie 会持久化在 WebView 中。 |

> 以上都记录在案，改进方向见 [`CHANGELOG.md`](CHANGELOG.md)。
> 若你认为其中某项构成安全问题，**也欢迎按第二节的渠道报告**。

---

## 六、安全部署建议

**先读这份**：[`docs/SECURITY_DEPLOY.md`](docs/SECURITY_DEPLOY.md)
（组网优先 · 必设 PIN · 尽量 HTTPS · 不要在公共 WiFi 下明文连接）。

---

## 七、相关文件

| 文件 | 内容 |
|---|---|
| [`docs/SECURITY_DEPLOY.md`](docs/SECURITY_DEPLOY.md) | 安全部署清单（组网 / PIN / HTTPS / 发现机制） |
| [`PRIVACY.md`](PRIVACY.md) | 隐私政策 · 权限说明 · 本机存了什么 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 贡献指南（含隐私与体积红线） |
| [`CHANGELOG.md`](CHANGELOG.md) | 变更日志（安全相关改动会在此标明） |
