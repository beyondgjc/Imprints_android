# 印迹 · 权限清单说明

> 用途：应用商店上架材料（工信部 / 华为 / 小米 / OPPO / vivo / 应用宝 均要求），
> 同时作为**申报与实现的自查清单** —— 申报内容必须与 `AndroidManifest.xml` 完全一致，
> 不一致是商店驳回的高频原因。
>
> 复核时间：2026-10-07

---

## 一、当前实际声明的权限

以 `app/src/main/AndroidManifest.xml` 为唯一事实来源，共 **5 个**：

| 权限 | 危险级别 | 运行时申请 | 申请时机 |
|---|---|---|---|
| `ACCESS_COARSE_LOCATION` | 危险 | ✅ | 仅打开「位置」页且用户点击「允许定位」后 |
| `ACCESS_FINE_LOCATION` | 危险 | ✅ | 同上 |
| `INTERNET` | 普通 | ❌ 安装即有 | — |
| `ACCESS_NETWORK_STATE` | 普通 | ❌ 安装即有 | — |
| `ACCESS_WIFI_STATE` | 普通 | ❌ 安装即有 | — |

**冷启动申请权限数量：0。** 应用启动后不弹任何系统权限框。

---

## 二、逐项说明

### 1. 位置权限（ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION）

| 项 | 内容 |
|---|---|
| **用途** | 用户主动添加地点时，获取当前坐标以查询附近地点名称 |
| **必需性** | **非必需**。拒绝后可在同一页面用搜索框手动搜索地点，功能不受影响 |
| **申请时机** | 用户进入「位置」页 → 页面底部显示「点击允许，查看附近地点」→ 用户点击后弹系统框 |
| **首次进入是否弹窗** | **否**。仅显示一行可点引导，不打断浏览 |
| **后续点击** | **每次点击都会拉起系统权限框**。用户已通过点击表达意图，系统允许重复询问 |
| **永久拒绝后** | 系统不再弹框，且**立即以拒绝结果回调**。此时在回调里检测到 `shouldShowRequestPermissionRationale` 两个权限均为 `false`，弹窗引导至系统设置；也可选「用搜索就好」直接进搜索 |
| **是否后台定位** | **否**。无后台定位、无前台服务、无位置监听 |
| **是否上传坐标** | **仅在你主动添加地点时**，向高德地图服务发一次坐标 + 查询关键词，用于返回候选地名。除此以外无任何网络请求 |
| **是否收集位置历史** | **否**。不保存轨迹、不缓存历史位置 |
| **配套声明** | `app/src/main/assets/privacy_policy.txt` 第二、五章已如实说明 |

**为什么同时声明 FINE 与 COARSE**：部分 ROM 上粗略定位易超时，两者都声明可让系统给出更精确的结果或更快成功。功能上不强制要求精确定位（城市级 POI 查询足够）。

### 关于「永久拒绝」的判断时机

Android 无法从 `shouldShowRequestPermissionRationale` 单独区分「首次申请」与「永久拒绝」——**两者都返回 `false`**。

本应用**不记录任何本地标记**，而是在权限回调里判断。依据是官方文档：

> 用户勾选「不再询问」后，系统不会再显示权限对话框，而是**立即以拒绝结果回调**（用户未做任何交互）。

因此：

- **首次点击** → 系统框正常弹出 → 回调时两个 rationale 仍为 `false`，但**不会走到这里**（只有拒绝才进判断分支）
- **拒绝一次（未勾不再询问）** → 回调时 rationale 为 `true` → 显示一行提示，不弹窗
- **永久拒绝后再点** → 系统立即拒绝 → 回调时两个 rationale 均为 `false` → 弹窗引导去设置

实现见 `LocationPickerPage.kt` 的 `permissionLauncher` 回调。

**代价**：永久拒绝后，用户**第一次点击引导按钮会「点了没反应」**（系统不弹框、也不回调给用户可见的提示），需要再点一次才看到设置引导。这是选择「不记录本地标记」的必然代价。换来的是零本地状态、逻辑更简单。已通过按钮副标题与提示行说明降低困惑。

### 2. 网络权限（INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE）

| 项 | 内容 |
|---|---|
| **用途** | 仅用于位置页向高德地图服务查询附近地点 |
| **是否必需** | 是（无网络时位置搜索不可用，其余功能正常） |
| **为何不可拒绝** | 这是**安装即授予**的普通权限，系统不提供运行时申请选项；不声明则位置搜索完全不可用 |
| **实际使用范围** | 印迹**没有账号系统、不做任何数据上报**。除高德 POI 查询外不发起任何网络请求；不集成统计、广告、推送 SDK |

### 3. 未申请的权限（重要）

以下权限**虽常见但本应用未声明**，属于有意克制，申报时不应出现：

| 权限 | 为何不申请 |
|---|---|
| `RECORD_SOUND` | 印迹**不自行录音**。「选择声音」时是 `Intent("android.provider.MediaStore.RECORD_SOUND")` **唤起系统录音机**，由系统应用自行申请麦克风。声明它属于过度索取 |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | 选图走系统 Photo Picker（`ActivityResultContracts.PickVisualMedia`），**不需要存储权限** |
| `READ_EXTERNAL_STORAGE` | 同上，Photo Picker 与 `OpenDocument`（SAF）均不需要 |
| `WRITE_EXTERNAL_STORAGE` | 应用只写自身私有目录（`filesDir/media`），不需要外部存储权限 |
| `READ_PHONE_STATE` | 不需要设备标识符 |
| `CAMERA` | 不拍照、不录像；动图与视频均从相册选取 |
| `POST_NOTIFICATIONS` | 无推送、无通知 |
| `QUERY_ALL_PACKAGES` | 仅通过 `<queries>` 声明了 `RECORD_SOUND` 一个 intent，用于在 Android 11+ 上 resolve 系统录音机 |

---

## 三、第三方 SDK 采集情况

> 版本对应：**印迹 1.4**（`versionName=1.4` / `versionCode=5`）。  
> 1.4 起不再分别依赖 `search` + `3dmap`，改为**一个聚合 Maven 坐标**。

| Maven 坐标 | 版本 | 内含组件 | 本应用实际用途 | 何时触达网络 / 采集 |
|---|---|---|---|---|
| `com.amap.api:3dmap-location-search` | `11.3.100_loc11.3.000_sea9.8.1` | **地图 Map**、**搜索 Search**、**定位 Location** | ① 位置详情页 `MapView` 底图预览；② 位置页附近/关键字 **POI**；③ 高德定位服务 API **本应用未调用** | **仅用户进入位置相关功能并主动使用时** |

**分量说明（申报时按此口径，避免写成「常驻定位 SDK」）**：

| 组件 | 本应用是否调用 | 数据 |
|---|---|---|
| **地图 Map** | 是（位置详情） | 瓦片请求（展示底图）；叠加 marker 坐标 |
| **搜索 Search** | 是（附近 / 关键字） | **当时坐标 + 查询关键词** 发往高德，返回地名/POI |
| **定位 Location** | **否**（随聚合包打入，**无 `AMapLocation` 调用**） | 运行时坐标由 **系统 `LocationManager`** 取得（见第四章权限），不经高德定位 SDK 上报 |

**合规门控**：

- 首启隐私同意 **之前** 不初始化上述任何组件（`MainActivity` 同意门控）。
- 用户首次使用 POI 前调用 `ServiceSettings.updatePrivacyShow` / `updatePrivacyAgree`（`AmapPoiService.ensurePrivacyAccepted`）。
- 高德 Key 仅经 Manifest `meta-data` 注入，不写入代码仓库。

**未集成**：统计 / 广告 / 推送 / 崩溃上报 / 用户行为分析 SDK。

**维护**：升级 `gradle/libs.versions.toml` 中 `amapCombo` 后，必须同步改本表、`privacy_policy.txt` 第五章与 `user_agreement.txt` 中的 SDK 描述。

---

## 四、商店申报对照速查

填「权限用途」时可直接引用：

- 位置 → *"用于用户主动添加地点时获取当前坐标以查询附近地点名称。不在后台定位，不保存位置历史。拒绝授权不影响其他功能，可手动搜索地点。"*
- 网络 → *"用于位置页向高德地图服务查询附近地点名称。本应用无账号系统，不上传任何本地数据。"*

**填写要点**：
1. 位置权限**不要写成"获取当前位置以提供个性化服务"** —— 与实际用途不符，是驳回点。
2. 声音相关**不要申报 RECORD_SOUND** —— 申报了但代码没申请，属于不一致。
3. 若被问及"是否间接获取麦克风"：说明为**唤起系统录音应用**，录音由系统应用处理，印迹不获取录音数据（隐私政策第五章已写入该说明）。

---

## 五、撤回同意时的处理

用户可在「设置 → 关于 → 撤回同意」撤回。撤回后应用退出，本机数据保留；同意状态存于 `SharedPreferences`（`imprints_consent`），因 `allowBackup="false"` 不参与系统云备份，换机后视为未同意并重新征求。

---

*维护提示：新增/删除权限或升级高德聚合包时，必须同步更新本文件、`app/src/main/assets/privacy_policy.txt`（第四、五章）与 `user_agreement.txt`，并重跑 `grep uses-permission app/src/main/AndroidManifest.xml` 与 `libs.versions.toml` 中 `amapCombo` 核对。*
