# 印迹 Android

本地单用户印迹应用。事件时间精确到分钟；图片、动图、视频合计最多 9 个，音频最多 1 个。感受默认明文保存但浏览时有刮开涂层，可选开启独立的 6 位口令加密。应用锁使用另一组 6 位口令。两种锁均可启用强生物识别，不使用系统设备凭证。

## 构建

先在 Penly 源码工程发布同版本的密码学工件，再构建本工程：

```powershell
cd C:\Users\beyondguo\Documents\zai\penly
.\gradlew.bat :crypto-core:test :crypto-core:publishToMavenLocal
cd C:\Users\beyondguo\Documents\Agents\Imprints\Imprints_android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

固定坐标为 `com.beyondguo.penly:crypto-core:0.1.0`，工件由 Penly 源码发布，不复制源码。Room schema 输出到 `app/schemas/`。

## 当前验证状态（2026-09-22，本机实测）

- Gradle：`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` BUILD SUCCESSFUL（Android Studio JBR）。
- JVM 单元测试共 **29** 项通过：备份帧 5 + 备份头 3、EntryRules 校验/搜索/回收站 9、MediaKindDetector 3、PinPolicy 3、TimelineLocator 3、Wgs84GCJ02 2（另有 Example 占位）。
- `mavenLocal` 中已有 `com.beyondguo.penly:crypto-core:0.1.0`。
- Git：独立仓 `Imprints_android`；远端 **GitHub 私有** `beyondgjc/Imprints_android`，`master` 与 origin 同步（勿提交 `local.properties` / Key / 备份口令）。
- 历史设备记录（改 UI 前）：
  - Android 15 模拟器（emulator-5554，API 35）：时间线、吸顶、涂层、FAB 正常。
  - Android 10 真机 MI 8（e1b8c4bc，API 29）：安装、时间线、高德附近/搜索/选点曾通过。
- **2026-09-22 bug 批次 T13–T21（已编码 + 构建/单测通过，设备逐条冒烟未做）**：
  - T13 时间线整列可点进详情；T14 搜索回时间线定位可上下滑；T15 全屏看图/视频 pager；T16 音频行尾 ×；T17 详情媒体高度约束；T18 位置详情页；T19 事件时间底部弹层 + 时分**循环滚轮**；T20 时间线位置与时间同行、编辑页位置在图片下；T21 回收站进详情；搜索页自动聚焦；矢量启动图标。
- 高德 POI（T08，已编码 + 真机附近/搜索/保存曾通过）：
  - Key 在 `local.properties`（`AMAP_ANDROID_KEY`，已 gitignore），Manifest 占位符注入。
  - 微信式「所在位置」→ `LocationPickerPage`；`DeviceLocation` last known + 多 provider；POI → `PlaceEntity(GCJ-02)`；`geo:` 前 GCJ→WGS84。
- 尚未完成：本批 UI 设备冒烟；PRD YJ27/YJ28 逐条验收（交付包 TODO T14）；生物识别、备份往返、7 天到期等（交付包 TODO T13）；T08 拒定位/无地图/Release SHA1 正式 Key。

高德 Android Key 在本机 `local.properties`（勿提交）。Release 签名与 SHA1 见下。

### Release 1.0（2026-09-22）

- `versionName=1.0`，`versionCode=1`
- **R8 已开启**（`optimization.enable = true` + `proguard-android-optimize.txt` + `app/proguard-rules.pro`）：收缩/优化/混淆；开源仓以效率与体积为主
- `mapping.txt`：`app/build/outputs/mapping/release/mapping.txt`（约 50MB+，**崩溃解混淆必存**，勿当无用文件删）
- 签名：`keystore/imprints-release.keystore`（**gitignore，勿提交**），配置在根目录 `keystore.properties`（gitignore）
- 构建：`.\gradlew.bat :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
- **Release SHA1**（apksigner 实测，用于高德控制台）：`7E:39:92:0D:8E:C5:14:95:4B:70:72:4A:78:48:64:0D:49:9D:E8:BB`
- 包名：`com.beyondguo.imprints`
- 注意：高德一 Key 绑一 SHA1。控制台用上述 SHA1 绑好后，把 **release Key** 写入 `local.properties` 再重新 `assembleRelease`；debug 包请继续用原 debug Key / debug SHA1。
- 口令只在生成时展示过一次，请已存入密码管理器；丢失无法再签同一应用。
