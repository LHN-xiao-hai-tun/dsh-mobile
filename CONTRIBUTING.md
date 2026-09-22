# 贡献指南（CONTRIBUTING）

感谢你愿意为 `dsh-mobile` 出一份力。

这个项目的**全部价值在于「小」**：约 3 MB 的 WebView 容器，只做一件事 ——
让你在手机 / 平板上像原生 App 一样访问**你自己部署的** DeepSeek Harness。
因此下面第一条红线比任何代码风格都重要。

---

## 一、🔴 隐私与体积红线（违反即不予合并）

1. **不引入任何第三方 SDK**（统计、广告、崩溃上报、推送、热更新……一律不要）
2. **不做任何网络上报**：App 自身除访问你填的那个 DSH 地址外，不发起任何请求
3. **不预置任何服务器地址**，也不要有"默认服务""推荐服务器"之类的东西
4. **用户数据只留在本机** `SharedPreferences`（见 `Prefs.java` 顶部注释），不落别处
5. **不引入 Kotlin**：本项目是 Java + Gradle，保持单一语言便于维护
6. **注意体积**：新依赖要说明它带来多少 KB / MB；APK 明显变大需要在 PR 里交代理由

> 判据：**这个改动会不会让用户"被"连上某个不属于他的服务器，或者让 App 变重？**
> 答案是"会"，就先来 issue 里讨论，别直接写代码。

---

## 二、开发环境

| 项 | 版本 |
|---|---|
| JDK | **17+**（本机实测 JDK 21 可用） |
| Android SDK | **platform 34** + **build-tools 34.0.0** |
| Gradle | 8.9（仓库自带 wrapper） |
| AGP | 8.7.3 |
| minSdk / targetSdk | 26（Android 8.0）/ 34 |

配置 SDK 路径（`local.properties` **不入库**）：

```properties
sdk.dir=/path/to/android-sdk
```

## 三、构建与运行

```bash
# 推荐：用仓库自带 wrapper（走国内镜像，快）
./gradlew assembleDebug

# 或者用系统 Gradle
gradle assembleDebug
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 装到设备：`adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Release 包：`./gradlew assembleRelease`（当前用 debug 签名，正式签名另行处理）

⚠️ 提交 PR 前**必须在真机上实际点过**受影响的功能。只过编译不算验证。

## 四、代码风格

- **Java 17**，4 空格缩进，不引入 Kotlin / 不引入注解处理器
- UI 目前是**代码里手写构建**（`Activity` + `LinearLayout`），没有 layout XML —— 保持这个风格，除非有充分理由
- 文案用**中文**，与现有界面一致；用户可见字符串建议进 `strings.xml`
- 不新增 `TODO` 占位；不写"以后再补"的空分支
- 注释写**为什么**，不写"把 x 赋给 y"这类废话

## 五、提交信息规范

采用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/v1.0.0/)：

```
<type>(<scope>): <简短说明>

<可选的正文：为什么这么改>
```

常用 `type`：`feat` / `fix` / `docs` / `refactor` / `build` / `chore`
常用 `scope`：`theme` / `settings` / `about` / `splash` / `commands` / `ci`

## 六、版本与变更日志

- 版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)：`versionName` 递增，`versionCode` 必须同步 +1
- 用户可见的改动写进 `CHANGELOG.md` 的 `[未发布]` 段；发版时把它挪到对应版本标题下

## 七、发布（维护者）

1. 改 `app/build.gradle` 的 `versionName` / `versionCode`
2. 整理 `CHANGELOG.md`
3. `./gradlew assembleRelease`
4. 在 GitHub Releases 新建 tag（`vX.Y.Z`）并上传 APK
   —— **APK 不入库**（仓库根目录不放 APK，`.gitignore` 已忽略 `*.apk`）

## 八、🔒 安全问题

**不要通过 PR 或公开 Issue 披露未修复的漏洞。**

请走 [`SECURITY.md`](SECURITY.md) —— 那里写了报告渠道、响应预期，以及
**当前已知的设计取舍**（明文流量 / 混合内容 / 网页权限授予 / 本机数据未加密）。

若你的改动**放宽**了任何安全相关设置（明文流量、混合内容、WebView 文件访问、
网页权限授予……），请在 PR 描述里写清**威胁模型**与**替代方案** —— 否则不予合并。

## 九、行为准则

请保持友善、就事论事。参见 [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md)。

## 十、隐私提醒（也适用于贡献者）

提交 Issue / PR 时**不要粘贴**：真实服务器地址、端口映射、PIN、token、带凭据的截图。

- 需要地址时用 `192.168.x.x` 这类**占位**
- 截图请先打码（本仓库已用 `.gitignore` 忽略 `screenshots/_*.png` 这类临时截图，
  就是因为它可能含私人会话内容）
