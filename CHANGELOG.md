# 变更日志

本项目所有重要变更都记录在此文件。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

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
