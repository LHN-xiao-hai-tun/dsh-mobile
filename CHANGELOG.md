# 变更日志

本项目所有重要变更都记录在此文件。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

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

[未发布]: https://github.com/LHN-xiao-hai-tun/dsh-mobile/compare/v1.1.1...HEAD
[1.1.1]: https://github.com/LHN-xiao-hai-tun/dsh-mobile/compare/v1.1...v1.1.1
[1.1]: https://github.com/LHN-xiao-hai-tun/dsh-mobile/releases/tag/v1.1
