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
- JVM 单元测试共 27 项通过：备份帧 5 + 备份头 3、EntryRules 校验/搜索/回收站 8、MediaKindDetector 4、PinPolicy 3、TimelineLocator 3（另有 Example 占位）。
- `mavenLocal` 中已有 `com.beyondguo.penly:crypto-core:0.1.0`。
- 新 APK 已安装并冷启动：
  - Android 15 模拟器（emulator-5554，API 35）：时间线、首月吸顶、感受绿涂层、FAB 正常，无 FATAL。
  - Android 10 真机 MI 8（e1b8c4bc，API 29）：安装成功、进程存活、时间线渲染正常。
- 本轮已修：时间线正文默认两行、首月 sticky 吸顶与定位索引对齐、关闭感受加密一律先验 PIN、搜索排序补 `id DESC`、备份导入不再写入孤儿地点、回收站恢复失败/成功提示、倒计时「不足 1 天」。
- 尚未完成：PRD YJ27/YJ28 逐条设备验收记录（T14）；生物识别、备份换机往返、7 天到期、持久层明文取证等设备用例（T13）；高德 POI（T08，待 Key）。

高德 Android Key 尚未提供，POI 搜索和附近地点仍待接入。当前支持手填地点、粗略当前位置和外部地图跳转。
