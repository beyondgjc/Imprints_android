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

## 当前验证状态（2026-09-23 更新）

- Gradle：`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` BUILD SUCCESSFUL（Android Studio JBR）。
- JVM 单元测试共 **29** 项通过（备份帧/头、EntryRules、MediaKindDetector、PinPolicy、TimelineLocator、Wgs84GCJ02 等）。
- `mavenLocal` 中已有 `com.beyondguo.penly:crypto-core:0.1.0`。
- Git：独立仓；远端 **GitHub 私有** `beyondgjc/Imprints_android`。勿提交 `local.properties` / keystore / 口令。
- **2026-09-23 迭代（已编码 + 构建；真机冒烟未系统做）**：
  - **1.0 签名发布**：keystore + GitHub Release `v1.0`；Release SHA1 见下
  - **R8**：`optimization.enable` + `proguard-rules.pro`；APK ~9.4MB；`mapping.txt` 必备份
  - **图标**：绿底白卡刮痕 + 右下金星（第一版定稿）
  - **刮卡**：真局部刮、80% 全开、四周金闪、无全开按钮、同 epoch 保持敞开
  - **时间线**：搜索在吸顶栏日历左侧；↑ 回到顶部；位置与时间同行
  - **详情**：顶栏右上「编辑」
  - **编辑页**：无媒体分区标题；音乐在位置上、同位置行 UI；宝藏副标题已删；底部提示居中
- **2026-09-22 bug 批次 T13–T21**（编码+构建过，逐条冒烟未做）：详见根目录交接文档。
- 高德 POI：Key 在 `local.properties`；微信式位置选择；MI 8 历史曾通过附近/搜索。
- 尚未完成：本日与 T13–T21 设备冒烟；交付包 TODO T13/T14；用 R8 包更新 v1.0 附件（若需要）；拒定位/无地图等 T08 边界。

高德 Android Key 在本机 `local.properties`（勿提交）。Release 签名与 SHA1 见下。

### Release 1.2（2026-09-24）

- `versionName=1.2`，`versionCode=3`
- 感受区 Meta 行折叠/旋转箭头；事件时间 Meta 行；键盘 ime 动态适配
- 防截屏+多任务单开关；时间线单图靠左、2 图 2 列
- **R8 已开启**；`mapping.txt`：`app/build/outputs/mapping/release/`（**必备份**）
- 构建：`.\gradlew.bat :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
- **Release SHA1**（不变）：`7E:39:92:0D:8E:C5:14:95:4B:70:72:4A:78:48:64:0D:49:9D:E8:BB`
- 包名：`com.beyondguo.imprints`
- GitHub：`https://github.com/beyondgjc/Imprints_android/releases/tag/v1.2`

### Release 1.1（2026-09-23）

- `versionName=1.1`，`versionCode=2`
- 相对 1.0：真实局部刮卡 + 80% 全开金闪、吸顶搜索、回到顶部、详情顶栏编辑、编辑页媒体/音乐/位置 UI、图标金星定稿、R8 等（见交接 09-23）
- **R8 已开启**；`mapping.txt`：`app/build/outputs/mapping/release/`（**必备份**）
- 签名：`keystore/` + `keystore.properties`（gitignore）
- 构建：`.\gradlew.bat :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
- **Release SHA1**（apksigner）：`7E:39:92:0D:8E:C5:14:95:4B:70:72:4A:78:48:64:0D:49:9D:E8:BB`
- 包名：`com.beyondguo.imprints`
- GitHub：`https://github.com/beyondgjc/Imprints_android/releases/tag/v1.1`（推送后创建）
- Keystore 口令仅生成时展示过，**务必自存**。

### Release 1.0（2026-09-22～23）

- `versionName=1.0`，`versionCode=1`
- **R8 已开启**（`optimization.enable = true` + `proguard-android-optimize.txt` + `app/proguard-rules.pro`）
- `mapping.txt`：`app/build/outputs/mapping/release/mapping.txt`（**崩溃解混淆必存**）
- 签名：`keystore/imprints-release.keystore` + 根目录 `keystore.properties`（**均 gitignore**）
- 构建：`.\gradlew.bat :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
- **Release SHA1**（apksigner）：`7E:39:92:0D:8E:C5:14:95:4B:70:72:4A:78:48:64:0D:49:9D:E8:BB`
- 包名：`com.beyondguo.imprints`
- GitHub：https://github.com/beyondgjc/Imprints_android/releases/tag/v1.0
- 注意：高德一 Key 绑一 SHA1；控制台绑好后把 **release Key** 写入 `local.properties` 再 `assembleRelease`。Keystore 口令仅生成时展示过，**务必自存**。
