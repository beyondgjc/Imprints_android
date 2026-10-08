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

## 当前验证状态（2026-10-07 更新）

- Gradle 标准构建 `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` **BUILD SUCCESSFUL**（Android Studio JBR 21）。
- JVM 单元测试 **29** 项通过（备份帧/头、EntryRules、MediaKindDetector、PinPolicy、TimelineLocator、Wgs84GCJ02 等）。
- 当前版本 **1.4（versionCode 5）**；见下方 Release 1.4。
- Git：独立仓；远端 **GitHub 私有** `beyondgjc/Imprints_android`。勿提交 `local.properties` / keystore / 口令。
- 高德 Key 在本机 `local.properties`（勿提交）。地图/POI 使用 `3dmap-location-search`（MAP so **16KB 对齐**）。
- 真机：MI 8 实测选图回锁、系统录音机、生物识别弹窗、位置详情地图；加密/备份等以设备冒烟为准。

## Release 历史

### Release 1.4（2026-10-07）

- `versionName=1.4`，`versionCode=5`
- **位置详情真地图**：高德 `MapView` 预览，缩放/平移；无坐标仍用示意占位
- **16KB 设备兼容**：`com.amap.api:3dmap-location-search:11.3.100_loc11.3.000_sea9.8.1`  
  （`libAMapSDK_MAP_v11_3_100.so` LOAD=16384；单独 `3dmap`≤10.0.600 仍为 4KB，已弃用）
- **添加声音**：来源弹层（从文件 / **系统录音机**）；仅已选中时显示「移除声音」；播完再点从头播放
- **生物识别**：MIUI `canAuthenticate=-1` 不再误判不可用；冷启动已开应用锁指纹则自动拉起；指纹注册期间 `onStop` 不清感受会话
- **选图/系统页回锁修复**：临时跳系统 Activity 期间不上锁、不清会话
- **设置页**：感受加密/应用锁开关 + 缩进子项；分组卡密度对齐 Penly
- 隐私政策、权限永久拒绝引导、文案整理
- CI：GitHub Actions（构建 + 单测 + lint）
- R8：`mapping.txt` → `app/build/outputs/mapping/release/`（**必备份**）
- 构建：`.\gradlew.bat :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
- 包名：`com.beyondguo.imprints`；Release SHA1 与 1.2 相同（keystore 未变）  
  `7E:39:92:0D:8E:C5:14:95:4B:70:72:4A:78:48:64:0D:49:9D:E8:BB`
- GitHub：`https://github.com/beyondgjc/Imprints_android/releases/tag/v1.4`

### Release 1.3

- `versionName=1.3`，`versionCode=4`；tag `v1.3`（`git log v1.2..v1.3`）

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
