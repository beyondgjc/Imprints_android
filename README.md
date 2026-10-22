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
├── MainActivity.kt        # 入口、生命周期、生物识别、防截屏(FLAG_SECURE)、通知深链消费
├── data/                  # Room、Repository（事务/互斥）、媒体文件、EntryRules 纯规则
├── mail/                  # 时光邮箱：送达调度、通知、后台策略
├── review/                # 回顾：日期范围与统计口径（纯函数）
├── security/              # 感受加解密、应用锁、PIN 退避（依赖 Penly crypto-core）
├── backup/                # 加密备份帧格式与导入导出
├── location/              # 设备定位、高德 POI、WGS84↔GCJ02
├── privacy/               # 防截屏/多任务开关
└── ui/                    # Compose：时间线、详情、编辑、搜索、设置、回收站、刮卡、时光邮箱、回顾
```

## 当前验证状态（2026-10-22 更新）

- Gradle 标准构建 **BUILD SUCCESSFUL**：`assembleDebug`、`assembleRelease`（R8 + lint `abortOnError`）、`testDebugUnitTest`（Android Studio JBR 21）。
- JVM 单元测试 **86 项通过**（15 个测试类；比 1.5 发布时的 53 项多出信件规则 / 日期范围 / 统计口径等 33 项）。
- 设备自动化 **43 项通过**（模拟器 `Medium_Phone_API_35` / API 35，含数据库迁移全链与仓库事务）。
- 版本 **1.5（versionCode 6，未发新版）**；时光邮箱与回顾的 V0.2 改动已合入 master，**尚未发版**。
- **时光邮箱与回顾（数据库 v6）已实现**，详见下方章节。数据库 **version 6**，新增 8 张表。
- Git：独立仓；远端 **GitHub 私有** `beyondgjc/Imprints_android`。勿提交 `local.properties` / keystore / 口令。
- 高德 Key 在本机 `local.properties`（勿提交）。地图/POI 使用 `3dmap-location-search`（MAP so **16KB 对齐**）。
- **POI 关键字检索已实测可用**（2026-10-12，MI 8 真机输入 `Starbucks` 返回真实结果），说明 Key 已绑定当前签名；换签名需在高德控制台重新绑定。
- 真机：MI 8 实测选图回锁、系统录音机、生物识别弹窗、位置详情地图、时光邮箱的写信/拆信/通知跳转；手工时序类用例尚未执行（见 `产品设计/…/05_验收清单.md`）。

## 标签：预置 + 用户自定义（1.5 起发布）

印迹变多后时间线全量平铺会乱，本次加入**标签分类 + 时间线按标签筛选**，并允许用户自建标签。

> 上一版（`9de1560 feat：固定标签`，数据库 v3）只支持 5 个写死在 `data/TagCatalog.kt` 里的预置标签；
> 本次把标签变成**库里的用户数据**，为此升到 v4。下面同时记录两版的行为与迁移。

### 预置标签与自定义

- **预置标签**：家事 / 国事 / 天下事（`TagCatalog.PRESETS`，id 仍是 `personal` / `national` / `internet`）。**允许改名**（用户可见文案），但 id 不变、身份仍是预置、**不可删除**（`TagCatalog.isPreset` 保护，因为库层面没有 `preset` 之外的标记）。旧预置「工作 / 旅行」自 1.5 起**停用**（`archived = 1`，见 `MIGRATION_4_5`），不再出现在选择与筛选里。
- **自定义标签**：编辑页/时间线点「+ 新建」输入名称；设置页「标签管理」可改名、删除、查看使用数。
- **重名复用**：名称已存在（忽略首尾空白、大小写、全半角 NFKC）时不新建，直接返回已有标签并选中——用户在编辑页想建一个已存在的标签，期望的是「用上它」而不是报错。
- **命名规则**（`data/TagRules.kt`）：去首尾空白、连续空白压成一个空格；**最多 20 个字**（按 Unicode 码点算，emoji 不会被算成两个）；**最多 50 个标签**（不含已停用）。
- **删除**：只解除与印迹的关联，印迹本体不动；删除前提示「N 条印迹将解除这个标签」。更改名称不影响已挂载关系（`entry_tags` 存 id）。

### 挂载与筛选

- **一条印迹挂 0~N 个标签**：`tags` + `entry_tags` 两张表；`EntryDraft.tagIds` / `EntryDetail.tagIds` 承载；保存时按展示顺序归一化（丢弃库内不存在的 id、去重、预置在前自定义在后）。外键 CASCADE，删印迹自动清关联，不留孤儿行。
- **时间线筛选**：标题下方**固定单行**标签 chip，横向滚动；**行内只放内置标签**（家事 / 国事 / 天下事），首位「全部」（未选任何标签时高亮，点它即清空筛选），末尾「更多」打开完整标签面板（搜索、使用数、新建、改名、删除）。可多选叠加（**OR 语义**），再点取消。已选中的自定义标签会临时留在行内，方便点掉。筛选在内存里做（条目数有限），没有新增 DAO 查询。
- **筛选联动**：月份吸顶、时间穿梭（月份/日期绿点）、搜索回跳定位都以**筛选结果**为准；筛选无结果时用独立空态文案，不误报「还没有印迹」。筛选状态由 `ImprintsApp` 持有，进出详情/编辑后保留。
- **管理入口**：编辑页与时间线的标签区都支持**长按已选标签**改名/删除；设置页 →「标签管理」列出全部标签与使用数，可改名、删除、新建。
- **详情页**：只读展示已挂标签，名称按库内查询（自定义标签名不写在代码里）。

### 备份与数据库

- **备份**：metadata 增加 `tags` / `entryTags`；`preset` / `createdAtMs` 一并导出。导入端 `optJSONArray` 兜底，**旧备份（无这些字段/数组）仍可导入**；标签按 id 合并，本机已有 id 不被覆盖，外部超长名称截断到 20 字而不是丢弃关联。
- **数据库迁移**：`version = 5`。`MIGRATION_3_4`（`tags` 加 `preset` / `createdAtMs`，按 id 把当时的 5 个预置标签回填为 `preset = 1`）；`MIGRATION_4_5`（预置「工作 / 旅行」置 `archived = 1` 停用），schema 导出到 `app/schemas/…/4.json`、`5.json`。
- **建库路径**：全新安装不跑 Migration，靠 `SEED_TAGS_ON_CREATE` 种预置标签——这条路径曾漏掉，导致新装用户编辑页标签区整段消失。

### 测试

- `TagRulesTest`（JVM 15 项）：命名归一化、NFKC/大小写/空白重名比较、20 字与 emoji 码点、排序与归一化。
- `TagCatalogTest`（JVM 6 项）：预置目录 id/名称/序号稳定、seed 标记、筛选语义。
- `TagRepositoryTest`（设备 8 项）：Room 映射、挂载归一化、整体替换、外键级联、筛选。
- `CustomTagRepositoryTest`（设备 11 项）：新建/重名复用/与预置同名/空名与超长/50 个上限、改名与冲突、删除只解关联、预置不可删、预置改名后身份不变。
- `ImprintMigrationTest`（设备 2 项）：用 `2.json` / `3.json` 的原始 DDL 建旧库后让 Room 真实升级，覆盖 **v3→v4（真机用户主路径）** 与 v2→v4 链，校验结构、预置回填、旧数据留存。
- `FreshInstallTagSeedTest`（设备 2 项）：全新安装种预置标签、重开幂等。

### 仍未验证

- 升级到真机旧库后的**肉眼回归**：自动化迁移测试已覆盖 Room 结构校验与旧数据留存，但真机上此前的旧数据已被清掉（安装前 `databases/` 即为空），所以**没有在真机现有旧数据上走过一次真实升级**（当前库为 v5）。
- 自定义标签相关交互的**视觉验收截图**（新建对话框、长按菜单、标签管理页）。

## 时光邮箱与回顾（数据库 v6，V0.2 已实现，未发版）

三个一级入口：**印迹 · 回顾 · 时光邮箱**。邮箱是"收信处"，回顾是"把一段时间缝起来"。

### 写信与封存

- **入口**：印迹「＋ → 给未来写信」、时光邮箱「写一封信」、回顾结语「给下一阶段的自己写信」——同一个编辑器。
- **规则**：标题可选（空则显示「给未来的自己」）；**正文必填**（普通印迹仍可只发媒体）；媒体合计 ≤ 9、音频 ≤ 1；送达时间精确到分钟，**提交时重新校验必须晚于当前时刻**；快捷项「一个月后 / 一年后」固定上午 9:00，目标日不存在时取当月最后一天。
- **封存后隐去**：未到期的信**不出现在邮箱、普通时间线、搜索、回顾候选和首页提醒中**，也不显示已封存数量、标题、倒计时或日历标记。邮箱只有「已送达 / 草稿箱」两个标签（**没有「待送达」**），草稿箱标签在没有草稿时不显示。
- **封存后正文与附件冻结**：首版不提供编辑。`LetterRepository.seal()` 只接受新建（`draft.id` 必须为空），`saveDraft()` 同样拒绝给已封存的信留可编辑草稿。

### 到期、拆信与已读

- 到期信进入「已送达」，未读在首页置顶。
- **未读**：点卡片进信封页 → 主动「拆开这封信」→ 正文成功展示后才写 `deliveryReadAtMs`。仅进信封、点通知、清除通知都不算已读。
- **已读**：再点一律**直达正文**，不再出现信封页与拆信动作；配色也从绿色未读态恢复为普通色（标题 `Ink`、日期 `Quiet`）。
- **数据层硬边界**：`LetterRepository.letterDetail()` 对**未到期或已进回收站**的信直接返回 `null` —— 不靠界面判断，防住通知点击、旧路由、进程恢复、缓存等旁路。

### 管理、删除与回收站

- **唯一管理入口**：设置 →「管理封存信件」。列表**只有「封存于 / 送达于」两个日期**，整行不可点、不显示标题/正文/附件；只能**改期**（只写送达元数据 + 递增 `scheduleRevision`）或**删除**（进 7 天回收站）。
- **删除分流**：正式信件（已送达/已封存）→ 7 天回收站，可恢复；草稿箱里的独立草稿 → 确认后**直接删除**，不进回收站。
- **回收站**：未到期的封存信不显示标题（列表回落为「封存信件」），也不提供正文预览，必须恢复后再走正常拆信流程。
- 信封页没有删除入口（按产品要求）：未读信需先拆开，再在正文页底部删除。

### 通知

- 独立渠道「时光邮箱」，普通可听提醒，不用全屏通知或持续响铃。
- 通知只带**信件 ID**，不带标题/正文/附件。点击后先过隐私同意与应用锁，再校验存在性、删除状态与到期状态，并按最新 `deliveryReadAtMs` 分流：未读→信封页，已读→正文。
- 多封信一起补发时合并为「你有 N 封来自过去的信」，点击进入**时光邮箱列表**（`EXTRA_OPEN_MAIL`）。
- 后台可靠性：可申请电池优化豁免；`LetterDueWorker` 每 15 分钟兜底对账一次。**已豁免时设置页那行会直接提示"无需设置"**（已豁免的 App 不出现在电池优化列表里，跳过去也看不到自己）。

### 回顾

- 按周期（周 / 月 / 年 / 自选）构建：先按日期范围 + 标签 OR 规则选出**纳入集合**，再单独标记**精选集合**；统计只从可见纳入集合计算，信件贡献单列。
- 保存时把来源内容**快照**（正文、标签、媒体引用）写入 `review_sources` / `review_source_media`，来源之后被改被删不影响已保存的总结；来源移入回收站时贡献同步隐藏，恢复时还原。
- 到期且**已确认阅读**的信可由用户主动加入总结；未拆开的信不会被回顾自动读取。

### 相关文件

| 文件 | 职责 |
|---|---|
| `data/FutureLetter.kt` | 信件与通知事件实体、列表投影、封存管理页的最小投影 |
| `data/LetterRepository.kt` | 写信/封存/改期/已读/回收站，全部走 `core.exclusive` 互斥 |
| `data/ReviewRepository.kt`、`data/ReviewPropagation.kt` | 总结保存、快照构建、来源删除传播 |
| `mail/LetterRules.kt`、`LetterScheduler.kt`、`LetterNotifier.kt`、`LetterBackgroundPolicy.kt` | 纯规则、调度与对账、通知与渠道、后台策略 |
| `ui/MailPages.kt`、`ui/SealedLettersPage.kt`、`ui/ReviewPages.kt` | 邮箱/信封/正文/写信、管理封存信、回顾向导与详情 |

## 历史：固定标签版（2026-10-10，`9de1560`，数据库 v3）

- 5 个预置标签写死在 `TagCatalog`，不支持用户自建；`tags` + `entry_tags` 表结构与筛选逻辑与现在一致。
- `MIGRATION_2_3` 与 `3.json` 保留在仓库里，供 v2/v3 老库升级使用。

## Release 历史

### Release 1.5（2026-10-16）

- `versionName=1.5`，`versionCode=6`
- **标签：预置 + 用户自定义**：标签从写死在代码里变成库内用户数据，可新建、改名、删除（预置不可删）；一条印迹可挂 0~N 个标签；名称最多 20 字、库内最多 50 个
- **时间线按标签筛选**：固定单行横滚 chip，首位「全部」（点它清空筛选）；行内只放内置标签，自定义标签收进「更多」；「更多」打开完整标签面板（搜索 / 使用数 / 新建 / 改名 / 删除）；多选为 **OR 叠加**
- **内置标签收敛**：家事 / 国事 / 天下事（原「个人经历 / 国家大事 / 互联网热点」只改展示名，id 不变）；「工作 / 旅行」**停用**（`MIGRATION_4_5`，数据库 **version 5**）
- 编辑页：标签条目行移到「所在位置」下方；未选文案改为「添加标签」
- 工具：新增设备测试数据播种脚本（官网截图用）
- R8：`mapping.txt` → `app/build/outputs/mapping/release/`（**必备份**）
- 构建：`.\gradlew.bat :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`（约 77MB）
- 包名：`com.beyondguo.imprints`；Release SHA1 与 1.2/1.3/1.4 相同（keystore 未变）  
  `7E:39:92:0D:8E:C5:14:95:4B:70:72:4A:78:48:64:0D:49:9D:E8:BB`
- GitHub：`https://github.com/beyondgjc/Imprints_android/releases/tag/v1.5`

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
