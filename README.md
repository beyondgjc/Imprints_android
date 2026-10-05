# 印迹 Android

本地优先的记忆应用：把正文、照片与「宝藏感受」收进一条时间线。全部数据只存在本机，**无注册、无登录、无云同步**。

- 事件时间精确到分钟；正文或视觉媒体至少填一项
- 图片、动图、视频混选合计最多 9 个；音频最多 1 个（重新选择即替换）
- 感受默认明文保存，但浏览时**始终先显示可刮开的绿色涂层**；可选开启独立的 6 位口令加密
- 感受口令与应用锁口令是互不通用的两组 6 位数字；两种锁均可启用强生物识别，不使用系统设备凭证；解应用锁不自动解开感受
- 回收站保留 7 天；手动整库加密备份；防截屏与多任务保护（默认开）
- 搜索只查正文（FTS4），不索引感受

## 构建

先在 Penly 源码工程发布同版本的密码学工件，再构建本工程：

```powershell
cd C:\Users\beyondguo\Documents\zai\penly
.\gradlew.bat :crypto-core:test :crypto-core:publishToMavenLocal
cd C:\Users\beyondguo\Documents\Agents\Imprints\Imprints_android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

固定坐标为 `com.beyondguo.penly:crypto-core:0.1.0`，工件由 Penly 源码发布，不复制源码。Room schema 输出到 `app/schemas/`。

## 源码结构

```
app/src/main/java/com/beyondguo/imprints/
├── MainActivity.kt        # 入口、生命周期、生物识别、防截屏(FLAG_SECURE)
├── data/                  # Room、Repository（事务/互斥）、媒体文件、EntryRules 纯规则
├── security/              # 感受加解密、应用锁、PIN 退避（依赖 Penly crypto-core）
├── backup/                # 加密备份帧格式与导入导出
├── location/              # 设备定位、高德 POI、WGS84↔GCJ02
├── privacy/               # 防截屏/多任务开关
└── ui/                    # Compose：时间线、详情、编辑、搜索、设置、回收站、刮卡、时间穿梭
```

## 当前验证状态（2026-10-05 更新）

- Gradle 标准构建 `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` **BUILD SUCCESSFUL**（Android Studio JBR 21 本机实测）。
- JVM 单元测试共 **29** 项全部通过（备份帧/头、EntryRules、MediaKindDetector、PinPolicy、TimelineLocator、Wgs84GCJ02 等）。
- 当前版本 **1.2（versionCode 3）**；release APK 最近一次构建于 2026-09-24，此后合入的 UI 优化提交**尚未重打 release**。
- Git：独立仓；远端 **GitHub 私有** `beyondgjc/Imprints_android`。勿提交 `local.properties` / keystore / 口令。
- 真机冒烟：本日未系统复测；加密、备份、回收站、生物识别等主路径以设备实测为准。
- 高德 Key 在本机 `local.properties`（勿提交）。POI/附近在 Key 与接入条件具备前保持可用性验证。

### 近期迭代摘要（2026-09-23 ～ 09-25，已编码+构建）

- **1.0 签名发布**、**R8 混淆优化**（`mapping.txt` 必备份）、图标定稿（绿底白卡刮痕 + 右下金星）
- **刮卡**：真实局部刮开、80% 面积全开金闪、无全开按钮、同 epoch 保持敞开
- **时间线**：搜索在吸顶栏、↑ 回到顶部、单图靠左/双图 2 列、位置与时间同行
- **详情**顶栏右上「编辑」；**编辑页**无媒体分区标题、音乐与位置行布局、键盘 IME 动态适配
- **设置页**分组卡改版（对齐 Penly）、防截屏与多任务单开关、生物识别失败提示与开启前口令校验
- bug 批次 T13–T21（编码+构建过，逐条设备冒烟未做）

## Release 历史

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
- 相对 1.0：真实局部刮卡 + 80% 全开金闪、吸顶搜索、回到顶部、详情顶栏编辑、编辑页媒体/音乐/位置 UI、图标金星定稿、R8 等
- `mapping.txt`：`app/build/outputs/mapping/release/`（**必备份**）
- 签名：`keystore/` + `keystore.properties`（gitignore）
- GitHub：`https://github.com/beyondgjc/Imprints_android/releases/tag/v1.1`

### Release 1.0（2026-09-22～23）

- `versionName=1.0`，`versionCode=1`
- **R8 已开启**（`optimization.enable = true` + `proguard-android-optimize.txt` + `app/proguard-rules.pro`）
- `mapping.txt`：`app/build/outputs/mapping/release/mapping.txt`（**崩溃解混淆必存**）
- 签名：`keystore/imprints-release.keystore` + 根目录 `keystore.properties`（**均 gitignore**）
- GitHub：`https://github.com/beyondgjc/Imprints_android/releases/tag/v1.0`
- 注意：高德一 Key 绑一 SHA1；控制台绑好后把 **release Key** 写入 `local.properties` 再 `assembleRelease`。Keystore 口令仅生成时展示过，**务必自存**。
