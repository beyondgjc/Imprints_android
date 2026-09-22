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

- Gradle：`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` BUILD SUCCESSFUL（Gradle 9.5.0 + Android Studio JBR）。
- JVM 单元测试共 29 项通过：备份帧 5 + 备份头 3、EntryRules 校验/搜索/回收站 9、MediaKindDetector 3、PinPolicy 3、TimelineLocator 3、Wgs84GCJ02 2（另有 Example 占位）。
- `mavenLocal` 中已有 `com.beyondguo.penly:crypto-core:0.1.0`。
- 新 APK 已安装并冷启动：
  - Android 15 模拟器（emulator-5554，API 35）：时间线、首月吸顶、感受绿涂层、FAB 正常，无 FATAL。
  - Android 10 真机 MI 8（e1b8c4bc，API 29）：安装成功、进程存活、时间线渲染正常。
- 高德 POI（T08，已编码+真机附近/搜索/保存通过）：
  - Key 放在 `local.properties`（`AMAP_ANDROID_KEY`，已在 `.gitignore`），经 Gradle `manifestPlaceholders` 注入 Manifest，源码无明文 Key。
  - 编辑页仅「所在位置」入口行（微信式，默认不展示位置控件）；点击进入独立选择页：不显示位置、附近列表（距离|地址）、右上角搜索。
  - 定位：`DeviceLocation` 优先 last known，API 29 多 provider `requestLocationUpdates`（修 requestSingleUpdate 超时）；申请 FINE+COARSE。
  - MI 8 实测：附近列出「信鹏大楼 62m内…」等；选点回写编辑页并保存详情显示；`gugong` 搜索故宫通过；单测 29 项。
  - 「不显示位置」清空地点；`geo:` 导航前 GCJ-02→WGS84。
- 本轮已修：时间线正文默认两行、首月 sticky 吸顶与定位索引对齐、关闭感受加密一律先验 PIN、搜索排序补 `id DESC`、备份导入不再写入孤儿地点、回收站恢复失败/成功提示、倒计时「不足 1 天」。
- 尚未完成：PRD YJ27/YJ28 逐条设备验收记录（T14）；生物识别、备份换机往返、7 天到期、持久层明文取证等设备用例（T13）；T08 完整验收（拒定位路径、无地图应用、Release SHA1 正式 Key）。

高德 Android Key 已由用户提供并写入本机 `local.properties`（勿提交）。Release 签名尚未创建，上架前需用 release SHA1 另申请 Key；隐私政策需明示高德条款。
