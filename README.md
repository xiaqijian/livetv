# 电视直播（LiveTV）

海信安卓电视（Android 8.0 及以上）可用的直播频道 App，用遥控器操作，打开就能看。

本工程基于开源项目 [horsemail/yourtv](https://github.com/horsemail/yourtv)（MIT 协议）二次开发，改造点见文末。

## 功能

- **开箱可看**：内置一份公开频道源（央视、卫视、教育台），装完直接有列表
- **自己换源**：设置页「订阅源」里用遥控器输入 m3u / txt 订阅地址，重启后仍然生效
- **一键恢复**：同个对话框里可恢复内置默认源
- 遥控器方向键换台、频道分组、EPG 节目单、频道收藏（沿用上游能力）
- 已移除上游的测试码授权、个人收款入口、私有云端基础设施与在线更新检查

## 构建

本项目不在本地打包，统一走 GitHub Actions：

1. 把仓库推送到你自己的 GitHub（`main` 分支）
2. Actions 会自动执行 `testDebugUnitTest` 与 `assembleDebug`
3. 构建完成后在 Actions 页面下载构件 `livetv-debug-apk`，里面是 `livetv_v1.0.0.apk`

如需本地构建（需要 JDK 17 + Android SDK 35）：

```
./gradlew testDebugUnitTest assembleDebug
```

产物路径：`app/build/outputs/apk/debug/`

## 安装到电视

- U 盘：把 apk 拷进 U 盘，插到电视上，用电视自带的文件管理打开安装（首次需在设置里允许安装未知来源应用）
- 或局域网：电视开启开发者模式后用 `adb install livetv_v1.0.0.apk`

## 内置频道源怎么维护

`app/src/main/res/raw/channels.txt` 由脚本生成，数据来自 [iptv-org](https://github.com/iptv-org/iptv) 的中国频道列表：

```
# 1. 下载最新的中国频道列表，覆盖输入快照
#    https://iptv-org.github.io/iptv/countries/cn.m3u
# 2. 重新生成
powershell -File tools/gen-default-channels.ps1
```

频道中文名与分组在脚本的 `$map` 里维护，想加台、改台名直接改那里。

## 免责声明

- 本项目不提供、不存储、不分发任何音视频内容，只提供播放能力与一份第三方公开收集的频道列表地址。
- 频道流的可用性与合法性由对应源提供方决定，请自行确认你所在地区使用的合规性。
- 上游项目的测试码、云端账号体系与作者私有服务均已停用，与本项目无关。

## 许可

沿用上游 MIT 协议，见 `LICENSE`。原始版权归 horsemail/yourtv 作者所有。
