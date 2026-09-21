# 变更日志

本项目所有重要变更都记录在此文件。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

## [1.2.2] - 2026-09-21

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
