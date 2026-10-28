# 直播-海信电视直播App 功能卡片

> 模板：Spec-Lite v1.0 修订版。阶段 0 只填第 1 部分；第 2/3 部分在阶段 1 编码时填充。

## 1. 📌 功能描述（阶段 0 创建时填写）

- **一句话概述**：基于开源项目 `horsemail/yourtv`（MIT 协议）二次开发，产出一个能装在海信安卓电视上的直播 APK——开箱即有一份内置默认源可看，用户也能自己导入/替换 m3u 订阅地址。
- **用户与场景**：
  - 日常：坐在客厅用遥控器打开电视 → 直接进频道列表 → 上下键换台看直播。
  - 兜底：内置源失效时，在设置页用遥控器输入或手机扫码导入自己的订阅地址，立刻换源继续看。

- **验收条件**：
  - **AC-1 可安装**：CI 产出的 APK 能安装到海信安卓电视（Android 8.0+），在安卓电视应用列表中可见；应用名、图标为自有品牌，启动页与设置页不出现上游作者名称/捐赠/社群信息。
  - **AC-2 开箱可看**：首次启动且无任何用户配置时，应用自动加载内置默认源并展示频道列表；选中任一频道能出画面并出声音。
  - **AC-3 换台**：遥控器方向键上下切换频道，源本身正常时换台后 ≤3 秒出画面；返回键回到频道列表并保持本次会话的频道位置。
  - **AC-4 自定义源**：在电视端设置页（不需要借助手机）用遥控器输入 m3u/txt 订阅地址并保存后，频道列表切换为该源内容；杀进程重启后仍然生效。
  - **AC-5 可恢复**：设置页提供"恢复默认源"入口，点击后清空自定义源、重新加载内置默认源，并给出"已恢复"提示。
  - **AC-6 故障可诊断**：订阅地址不可达、超时或解析出 0 个频道时，界面给出明确中文提示，并提供"去导入自己的源"的引导入口；不白屏、不静默失败。

- **不做的事**（防范围蔓延）：
  - 不做点播/影视/搜索/Xtream/Stalker 门户
  - 不新增 EPG 节目单能力（沿用上游已有的 EPG 展示，仅确保不报错）
  - 不打包任何频道流内容本体，只内置"订阅地址 + 解析能力"
  - 不做应用内自动更新、不自建更新服务器（移除上游指向作者服务器的更新检查）
  - 不做多源并发管理、不做多用户配置
  - 不处理运营商 IPTV 的鉴权/组播

- **验证方式**：
  - 编译（CI 唯一可信来源）：GitHub Actions 执行 `./gradlew assembleDebug`，产物上传为 artifact。本机无 JDK / Android SDK / adb，**本地无法编译**，编译是否通过只以 CI 结果为准。
  - 单元测试：为 m3u / txt 解析逻辑补 JVM 单元测试，CI 执行 `./gradlew testDebugUnitTest`。
  - 手工验收：安装 APK 到电视，按 AC-1..AC-6 逐条走查，由我给出逐步操作清单、你在电视上执行并回传结果。

- **外部副作用**（逐项待授权，未授权不执行）：
  - [ ] 在你的 GitHub 账号下创建仓库并 push 代码（本机无 `gh`、无凭据，需你执行或授权后提供方式）
  - [ ] 触发 GitHub Actions 构建（消耗你账号的 Actions 配额）
  - [ ] 把 APK 安装到你的电视（adb 或 U 盘，由你操作）

- **已知风险（确认时请一并认可）**：
  - 内置公开源由第三方维护，随时可能失效；本方案的价值在于"开箱可用 + 可自己换源"，不承诺长期稳定，也不承诺包含全部央视频道。
  - 上游 MIT 协议允许修改分发，但需保留原作版权声明；上游 APK 内嵌了作者私有的 Cloudflare 授权、GitHub token 等基础设施，本次会一并移除，届时相关上游功能（如作者源列表拉取）不可用。

## 2. ✅ 验收清单（编码完成时填写）

| 验收条件 | 实现落点 | 状态 |
|---|---|---|
| AC-1 可安装 / 无上游品牌 | 包名改 `com.livetv.tv`、应用名三语言改「电视直播」/「電視直播」、设置页隐藏「赞赏」入口、禁用在线更新检查 | 待 CI 出 APK + 电视安装确认 |
| AC-2 开箱可看 | `res/raw/channels.txt` 换成 45 条中文频道（央视 / 卫视 / 教育）、`rawstablesource.txt` 置空使首屏不再播作者的台、首屏等待从 5s 降为 300ms | 待真机确认 |
| AC-3 换台 | 沿用上游 PlayerFragment / 遥控器按键处理，本次未改动 | 待真机确认 |
| AC-4 自定义源 | 设置页「订阅源」→ `dialog_source` 对话框输入地址 → `MainViewModel.importSourceFromUrl` → 写入 `active_source`，重启由 `loadActiveSource` 恢复 | 单测覆盖地址校验；端到端待真机 |
| AC-5 恢复默认源 | 对话框「恢复默认源」→ 清 `configUrl` + `MainViewModel.restoreDefaultSource()` | 待真机确认 |
| AC-6 故障可诊断 | 地址为空/非 http 有明确 toast；下载失败沿用 `sources_download_error`；解析 0 频道沿用 `channel_read_error` | 单测覆盖前两类 |

## 3. 📎 技术方案（编码时填写）

**基座选型结论**：`horsemail/yourtv`（MIT、Kotlin、Gradle KTS、minSdk 23 / compileSdk 35、Media3+ExoPlayer）。排除的候选：`lizongying/my-tv`（无 LICENSE，保留所有权利 + 声明禁止分发）、`mytv-android/mytv-android`（仅发布仓库，源码在私有分支）、`ahXN00/OwnTV`（依赖私有 maven 包与本机绝对路径签名文件）。

**主要改动清单**：
1. 包名 `com.horsenma.yourtv` → `com.livetv.tv`，遗留模块 `com.horsenma.mytv1` → `com.livetv.tv.legacy`（93 个 kt / 布局与资源同步替换）
2. 内置源：`tools/gen-default-channels.ps1` 从 iptv-org 中国频道列表生成 `res/raw/channels.txt`（中文台名 + 央视/卫视/教育分组）
3. 首屏：`MainViewModel.init` 的固定 5 秒等待改为「有稳定源才等 5 秒」
4. 订阅源 UI：新增 `res/layout/dialog_source.xml`，`SettingFragment.showSourceDialog()` 替换原测试码对话框入口
5. 授权与私有设施：`UserInfoManager` 的 `validateKey`／`downloadRemoteUserInfo`／`checkExpiredTestCodes`／`loadApiKeys` 全部短路；`DownGithubPrivate.getCachedPrivateRepos` 返回空使下载走普通 HTTP；`UpdateManager.checkAndUpdate` 关闭；`SimpleServer` 不再请求作者源列表；`Utils` 移除作者镜像域名；`rawstablesource/cloudflare/github_private/sources` 资源清空
6. 单测设施：`junit:4.13.2` + `app/src/test`，首个用例 `SourceUrlValidatorTest`（7 个）
7. CI：`.github/workflows/android.yml`（JDK 17 + setup-android，跑 `testDebugUnitTest` 与 `assembleDebug`，上传 APK 构件）

**验证状态（如实记录）**：本机无 JDK / Android SDK / adb，编译与单测均未在本地执行过，唯一可信验证来源是 GitHub Actions 结果；集成测试与 E2E 不具备条件（无设备无模拟器），由真机手工走查替代。
