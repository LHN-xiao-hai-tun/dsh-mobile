# 隐私政策（Privacy Policy）

**DSH Mobile 是一个本地客户端**，用于连接**你自己**运行的
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)。
它**不含 DSH 本体**、**不预置任何服务器地址**、**不采集任何数据**。

---

## 一、我们不收集

- ❌ 不收集个人身份信息
- ❌ 不收集设备标识符
- ❌ 不做使用统计 / 埋点
- ❌ 不集成广告 SDK
- ❌ 不集成第三方分析 SDK
- ❌ 不做崩溃上报（没有崩溃收集服务；系统 logcat 由你在设备上自行导出）
- ❌ 不访问通讯录、短信、相册、精确位置

App 自身**唯一的网络行为**，是访问**你填写的那个地址**。

---

## 二、本机保存了什么

以下内容**只存在本机** —— App 私有目录下的 `SharedPreferences`
（文件名 `dsh`，`MODE_PRIVATE`，仅本 App 可读）：

| 数据 | 键 | 说明 |
|---|---|---|
| 服务器地址 | `url` | 你填的那个 |
| 连接历史 | `history` | 最近 **5** 条（去重，最近在前） |
| 快捷指令模板 | `tpl_0` ~ `tpl_2` | 3 条可编辑文字 |
| 浮动按钮位置 | `fab_x` / `fab_y` | 存的是「可移动范围的比例」，旋转屏幕后仍合理 |

**没有保存**：DSH 的 PIN / 密码（App 不存 PIN —— PIN 在网页里输入，之后由 Cookie 承载登录态）、任何密钥。

### ⚠️ 已知限制（如实说明）

上述内容目前**以明文存放在 App 私有存储中**（**未加密**）：

- 在**未 root** 的设备上，其他 App **无法**读取该目录；
- 但设备**被 root** 或装有恶意软件时，这些内容**可能被读取**。
- ⇒ **不要把 PIN 之类的敏感凭据保存在设备上** —— 本 App 也确实不保存 PIN。

**加密存储**（`EncryptedSharedPreferences`）已列为后续改进项，见 [`CHANGELOG.md`](CHANGELOG.md)。

### 怎么清除

- 在「设置」页**清空地址**即可清除对应数据；
- **卸载 App** 会清除全部本地数据。

---

## 三、网络通信

- App **只与你配置的 DSH 地址通信** —— 不向任何第三方服务器发送数据。
- **登录态**通过 **Cookie** 保存在 WebView 中（`CookieManager`），用于免去反复登录；Cookie 只对该地址生效。
- **mDNS / NSD 发现**（v1.2.7 起）只在**局域网内**监听，用于发现 DSH 服务，**不向互联网发送任何内容**。
- **子网扫描兜底**：对同一子网做 **TCP 连接探测**（连上即断，**不发送任何应用层数据**），
  结果只留在内存，**不落盘、不上传**。
- 「关于 → 检查更新」**不会自己发请求** —— 它只是让系统浏览器打开 GitHub 仓库页面。

---

## 四、权限说明

App 在 `AndroidManifest.xml` 中**只声明 3 个权限**：

| 权限 | 用途 |
|---|---|
| `INTERNET` | 连接你配置的 DSH 服务 |
| `ACCESS_NETWORK_STATE` | 判断网络是否可用（网络恢复时自动重连，v1.2.5） |
| `CHANGE_WIFI_MULTICAST_STATE` | 获取组播锁（`MulticastLock`），用于局域网 mDNS/NSD 发现（v1.2.7） |

App **不申请**定位、相机、麦克风、通讯录、存储、电话、蓝牙等权限。

> **关于合并清单里多出的那一条（如实说明）**：
> 上面是**源码清单**里的声明。打包后的**合并清单**里还会多出一条
> `app.dshmobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
> —— 这是 **androidx 自动生成**的**应用自有签名级权限**（用于限制广播接收器不被外部 App 调用），
> **不涉及任何系统数据或用户信息**，也不会向用户暴露任何权限授予界面。
> 用 `aapt dump badging` 审计时会看到它，特此说明。

> 补充：mDNS 发现**本可**用「读 WiFi SSID」的方式做，但那需要**定位/近场权限** ——
> 本项目**刻意不申请**（见 `AndroidManifest.xml` 内注释）。

### 网页内的权限请求

若 DSH 网页里请求麦克风 / 摄像头（例如语音输入），App 会在 **WebView 层直接放行**
（`MainActivity.onPermissionRequest` → `request.grant(...)`），目的是让语音输入可用。

是否真正授予仍由 **Android 系统**决定；App **未**在清单中申请相机 / 麦克风权限。

---

## 五、明文流量说明

App 声明了 `android:usesCleartextTraffic="true"`，这是为了**支持局域网内的 `http://` 连接** ——
`dsh-pocket` 的局域网入口默认就是 `http://<电脑IP>:3081`。

> ⚠️ 明文连接的内容**可被同一网络下的他人窃听**。
> 请优先使用 **HTTPS** 或**组网**（Tailscale / ZeroTier / WireGuard），
> 详见 [`docs/SECURITY_DEPLOY.md`](docs/SECURITY_DEPLOY.md)。

---

## 六、儿童隐私

本 App 不面向儿童，也不收集任何用户数据。

---

## 七、政策变更

本政策如有变更，会在本仓库与本项目的 Release Notes 中说明。

---

## 八、联系

- **隐私相关问题**：请到
  [Issues](https://github.com/LHN-xiao-hai-tun/dsh-mobile/issues) 提出
  —— **不要贴真实地址、PIN 或任何凭据**。
- **安全问题**：请走 [`SECURITY.md`](SECURITY.md)（**不要公开披露细节**）。
