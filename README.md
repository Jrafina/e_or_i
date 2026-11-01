# e or i · 一句话记账

> 输入「吃饭花了支付宝20元」按回车，AI 解析成结构化账单并直接入账（可一键撤销）。
> 极简 Android 记账应用：Kotlin + Jetpack Compose + Material 3，单 Activity，MVVM + Repository。

![status](https://img.shields.io/badge/build-passing-brightgreen) ![minSdk](https://img.shields.io/badge/minSdk-26-blue) ![targetSdk](https://img.shields.io/badge/targetSdk-34-blue)

---

## 1. 快速开始

```bash
git clone <your-repo-url> e-or-i && cd e-or-i
./gradlew :app:assembleDebug   # 首次会从国内镜像下载依赖
```

### 环境要求

| 项目 | 版本 |
| --- | --- |
| JDK | 17 |
| Android SDK | compileSdk 36，build-tools 35/36 |
| Gradle | 8.13（使用仓库内 wrapper，无需本机安装） |
| AGP / Kotlin | 8.13.2 / 2.2.21 |

### 构建

```bash
# Debug APK（无需配置任何签名）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# Release APK（自动使用 keystore/ddl-release.jks 签名；缺失时回退 debug 签名，保证能出包）
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk

# 单元测试 + Robolectric 启动/UI 测试（共 75 个用例）
./gradlew :app:testDebugUnitTest
```

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 已构建产物

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `dist/eori-debug.apk` | 12.1 MB | debug 包，未混淆，可直接安装调试 |
| `dist/eori-release.apk` | 1.8 MB | release 包，R8 混淆 + 资源收缩，v2 签名 |

> `dist/` 与 `*.apk` 均已加入 `.gitignore`：**二进制不进仓库**，建议用 GitHub Releases 分发。
> 两个包签名不同，切换安装需先卸载。
> 本机构建 release 时关闭了 `lintVital`（`checkReleaseBuilds = false`），否则 2 核环境下打包会长时间阻塞。
> SHA-256：debug `05bcbb57…84fee1050`，release `52498ac0…459adfecb8`。

### 签名与密钥

仓库**不包含任何密钥**（`keystore/`、`keystore.properties`、`*.jks` 全部被忽略）。构建脚本按以下顺序取签名：

1. 本地 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）
2. 环境变量 `EORI_STORE_FILE` / `EORI_STORE_PASSWORD` / `EORI_KEY_ALIAS` / `EORI_KEY_PASSWORD`（适合 CI）
3. 都没有 → release 自动回退 **debug 签名**，保证 clone 下来就能出包

```properties
# keystore.properties（不要提交）
storeFile=keystore/release.jks
storePassword=******
keyAlias=******
keyPassword=******
```

### 国内网络镜像配置

本机在国内网络环境，已在工程内配置好镜像，**开箱即用**：

- `gradle/wrapper/gradle-wrapper.properties`：Gradle 发行包走腾讯云镜像
  `https://mirrors.cloud.tencent.com/gradle/gradle-8.13-bin.zip`
- `settings.gradle.kts`：依赖解析优先阿里云镜像，官方仓库兜底

  ```kotlin
  maven("https://maven.aliyun.com/repository/gradle-plugin") // 插件
  maven("https://maven.aliyun.com/repository/google")        // AndroidX / AGP
  maven("https://maven.aliyun.com/repository/public")        // 中央仓库代理
  google(); mavenCentral(); gradlePluginPortal()             // 兜底
  ```

如果镜像也不可达，可在 `settings.gradle.kts` 中调整顺序，或配置公司内网 Nexus 代理。

### 受限环境（容器 / CI）构建说明

沙箱内 `$HOME` 可能不可写，可把 Gradle 与 Android 的用户目录重定向到工程内：

```bash
export GRADLE_USER_HOME=$PWD/.gradle-home     # Gradle 缓存与 wrapper 解压目录
export ANDROID_USER_HOME=$PWD/.android-home   # debug.keystore 存放目录
./gradlew :app:assembleDebug
```

`gradle.properties` 中已设置 `kotlin.compiler.execution.strategy=in-process`，避免 Kotlin 编译守护进程在受限环境中连接失败。

---

## 2. 功能清单

### 首页 · 快速记账
- 启动即进入首页，输入框**自动聚焦并弹出键盘**。
- **按键盘回车（或输入法“发送”）即触发解析**，无需点右侧箭头；外接键盘的 Enter 也支持。
- **解析成功直接入账**，不再弹确认卡片：Snackbar 展示「已记支出 ¥20.00 · 支付宝 · 吃饭」，并提供**「撤销」**按钮兜底。
- 入账同时更新账户余额，并清空输入框，可立即记下一笔。
- 支持示例：「吃饭花了支付宝20元」「看多多视频微信收入0.3」。
- **降级保障**：未配置 AI、网络失败、超时、非 2xx、JSON 非法 → 自动切换手动表单，记账永不阻断。
- 最近账单列表，点击行或编辑图标可编辑（改金额/账户会同步修正余额），垃圾桶图标可删除（余额回滚）。

### 设置 · AI 配置
- 填写 Base URL + API Key，点「验证」调用 `GET {baseUrl}/models`。
- **自动兼容带 / 不带 `/v1`**：`https://api.openai.com`、`https://api.openai.com/v1`、`https://xx.com/compatible-mode/v1` 都能正确拼接，不会出现 `/v1/v1`。
- 验证成功拉取模型列表，下拉选择并保存。
- 失败区分**网络 / 鉴权（401/403）/ 接口不存在（404）/ 频率限制（429）/ 响应格式**，给出明确中文提示。
- API Key 默认掩码，可切换显示；仅存本地 DataStore，不上传任何第三方。
- 深色模式开关（默认跟随系统）。

### 统计
- **本月消费 Top10**：按金额从大到小排名，前三名金银铜徽标 + 相对占比进度条。
- 四个指标：**总支出 / 总收入 / 净支出（收入−支出）/ 月日均支出（净支出 ÷ 本月已过天数，天数 ≥ 1）**。
- 按日收支双柱趋势图（纯 Canvas 绘制，未引入图表库），空数据全 0 不崩溃。
- 可切换月份，禁止切到未来月份；切到非本月时按整月天数计算日均。

### 账户
- 顶部**总余额**卡片：所有账户余额之和（未填写余额的账户按 0 计），负数标红。
- **点击账户进入详情**：集中展示该账户的全部账单、当前余额、累计收入/支出。首页依旧展示全部账单，互不影响。
- 增删改查：名称、类型（银行卡 / 支付宝 / 微信 / 现金 / 其他）、可选余额。
- **余额随账单自动联动**：支出减少、收入增加；改金额/改账户先撤销旧影响再应用新值；删除账单把金额还回去。全部在同一个数据库事务内完成。
- **删除账户不级联删除账单**：历史账单保留，账户列显示为「已删除账户」，删除前会提示影响笔数。
- 首次启动自动预置支付宝 / 微信 / 银行卡 / 现金四个常用账户。

### 自然语言解析（解析即入账）

> 说明：解析成功后不再要求二次确认。为避免模型误判影响账本，每次自动入账都提供
> Snackbar「撤销」，且账单本身仍可在首页点开编辑。
- `POST {baseUrl}/chat/completions`，`temperature=0.1`，要求模型输出严格 JSON：
  `{amount, type, account, note, occurredAt}`
- **真实账户名会写入提示词**：模型必须从「卡1 / 卡2 / 支付宝…」中选一个；
  本地再加一层 `AccountMatcher`（精确名 → 数字区分 → 银行简称 → 类型兜底），
  确保「卡2消费2元」不会记到卡1。
- 提示词显式注入**当前时间、星期、时区**，并给出金额提取、收支判断、账户识别、相对时间（今天/昨天/前天/早上/晚上）的具体规则。
- 结果校验：金额 > 0、类型合法、账户归一到 5 类（无法识别归「其他」）、时间非法回落当前时间、未来时间纠正。
- 解析容错：支持 ```json 代码块、前后夹带解释文字、字段名大小写/下划线差异、`account` 为对象等常见模型输出瑕疵。

### 云同步（Cloudflare D1，手动触发）
- 设置页填写 **Worker 地址 + 同步密钥**，点「测试连接」只读探测，点「立即同步」执行。
- **按两端最后时间戳决定方向**：云端新→下载，本地新→上传，相差 <1 秒→已是最新。
- **只传增量**：1000 条里改了 3 条就只传 3 条；每批 200 条分页；判断方向只发一个 `meta` 请求。
- **删除同步**：删除为软删除（`deleted` 标记），传到另一端后一起消失，不会「复活」。
- **显示最近同步时间**与上下次同步摘要（拉取/上传 + 条数）。
- **安全**：强制 https、Bearer 密钥鉴权（恒定时间比较）、按 IP 限流；密钥只存本地与 Cloudflare Secret。
- **金额单位**：D1 中 `accounts.balance` 与 `transactions.amount` 均以**元**存储、两位小数；
  App 内部仍用「分」，仅在传输边界用 BigDecimal 精确换算。
- **换机/重装恢复**：用「是否有真实数据（有余额或账单）」判断，启动预置的空壳账户不算数据，
  因此重装后同步必定拉取云端全量。
- **协议版本**：App 与 Worker 都带 `PROTOCOL_VERSION=2`，版本不匹配直接报 409，
  避免新旧混用把 50 元二次换算成 0.5 元。
- 部署步骤见 [`server/README.md`](server/README.md)，Worker 源码在 `server/worker.js`。

---

## 3. 架构说明

```
app/src/main/java/com/ddl/eori/
├── EoriApplication.kt        # 应用入口：容器初始化、全局异常兜底、预置账户
├── MainActivity.kt           # 单 Activity：SplashScreen → Compose
├── di/AppContainer.kt        # 手动注入容器（替代 Hilt）
├── data/
│   ├── local/                # Room：Entities / DAO / Database / TypeConverter
│   ├── remote/               # OkHttp + kotlinx.serialization：URL 拼装、提示词、解析与校验
│   ├── repo/                 # AccountRepository / TransactionRepository
│   └── settings/             # DataStore(Preferences)：AI 配置与界面偏好
├── domain/Models.kt          # 领域模型、ParseError、账户类型映射
├── ui/
│   ├── EoriApp.kt            # Scaffold + 底部导航 + 统一 Snackbar
│   ├── AppViewModelFactory.kt# 集中式 ViewModel 工厂
│   ├── home/                 # 首页：输入 → 解析 → 确认卡片
│   ├── stats/                # 统计：指标 + Canvas 趋势图
│   ├── accounts/             # 账户 CRUD
│   ├── settings/             # AI 配置与验证
│   ├── components/           # 复用组件与自定义矢量图标
│   └── theme/                # Material 3 主题（浅色/深色）
└── util/Formatters.kt        # 金额（分）与日期工具
```

### 关键设计决策

| 决策 | 理由 |
| --- | --- |
| **MVVM + Repository，按 feature 分包** | 4 个页面规模，无需多模块；按 feature 分包让改动局部化，比按层分包更好维护。 |
| **手动注入 `AppContainer`** | 依赖只有 5 个单例，Hilt 会引入 KSP 处理与额外构建时间，收益为负。 |
| **单 Activity + `when` 切换页面，不引入 Navigation** | 4 个平级页面、无路由参数，用 `rememberSaveable` 保存选中 Tab 即可；ViewModel 挂在 Activity 上，旋转/切后台返回不丢状态。 |
| **金额用 `Long` 存「分」** | 彻底避免 `Double` 的 0.1+0.2 误差；只在输入/展示边界转换，`Money.parseToCents` 用 `BigDecimal` 半进位。 |
| **不做账户外键级联** | 需求要求删除账户保留账单；账单额外存 `accountName` 快照，UI 用 `accountId ∉ 账户集合` 判定「已删除账户」。 |
| **AI 结果不直接落库** | 先进入确认卡片，用户可改金额/类型/账户/时间，避免模型误判污染账本。 |
| **OkHttp + kotlinx.serialization，不用 Retrofit** | 只有 2 个接口，Retrofit 的注解与反射开销不划算；手写请求更易做超时与错误分类。 |
| **纯 Canvas 画图** | 避免引入 MPAndroidChart 等体积大、维护差的库；按日双柱足够表达趋势。 |
| **自定义 `AppIcons`** | `material-icons-extended` 会让 dex 膨胀 40MB+（实测 18.9MB → 见下方优化），只保留用到的 5 个矢量图标。 |

### 数据模型

```kotlin
Transaction: id, amountCents(Long), type(EXPENSE/INCOME), accountId?, accountName?,
             note, rawInput, occurredAt, createdAt,
             uid(跨设备标识), updatedAt(增量同步依据), deleted(软删除)
Account:     id, name, type(BANK/ALIPAY/WECHAT/CASH/OTHER), balanceCents?, createdAt,
             uid, updatedAt
设置(DataStore): ai_base_url, ai_api_key, ai_model, dark_mode,
                sync_endpoint, sync_token, sync_last_at, sync_last_summary
```

数据库版本 **v2**，附带 `MIGRATION_1_2`：老用户升级时自动补齐 `uid` / `updatedAt` / `deleted`，
数据零丢失（有专门的迁移测试覆盖）。

```text
```

### 线程与稳定性
- 所有网络、数据库、DataStore 操作均在 `Dispatchers.IO`；Compose 只消费 `StateFlow`，主线程无 IO。
- AI 调用超时：连接 10s / 读 25s / 总 35s；`Result` 包装，**任何异常都转成 `ParseError`，不向上抛**。
- 全局 `UncaughtExceptionHandler` 记录日志后交给系统默认处理，保证进程状态一致。
- Room 查询用 `IFNULL(SUM(...), 0)`，空账本统计返回 0 而非 null。

### 启动优化
- `SplashScreen API`：系统级启动屏，无白屏；仅保持到首帧，不做人为延迟。
- 首屏只订阅「账户 + 最近账单」两个 Flow；统计页数据在进入 Tab 时才通过 `flatMapLatest` 订阅。
- Room 数据库懒加载，`Application.onCreate` 只创建轻量容器；预置账户在 IO 线程异步执行。
- 移除非必要的 `material-icons-extended`（40MB+ dex）与 Coil，APK 体积与冷启动明显下降。

---

## 4. 测试

> Robolectric 需要 `android-all-instrumented` 运行时 jar（约 340MB）。工程已把缓存目录指向 `.robolectric/`，
> 若该目录为空，首次运行会尝试从 Maven 下载；离线环境可手动放入：
>
> ```bash
> curl -fSL -o .robolectric/android-all-instrumented-15-robolectric-12650502-i7.jar \
>   https://maven.aliyun.com/repository/public/org/robolectric/android-all-instrumented/15-robolectric-12650502-i7/android-all-instrumented-15-robolectric-12650502-i7.jar
> ```

```bash
./gradlew :app:testDebugUnitTest
```

覆盖（75 个用例）：

- `MoneyTest`：金额解析（符号/单位/千分位/非法值）、两位小数进位、正负号展示。
- `ApiUrlsTest`：Base URL 归一化、`/v1` 自动补全、不重复拼接、缺协议补 https、空值。
- `JsonExtractTest`：裸 JSON、```json 代码块、夹带解释文字、非 JSON、`account` 对象、字段名大小写/下划线。
- `ModelListParserTest`：OpenAI `data[]` 格式、`models[]` 兜底、HTML 错误页返回空。
- `DatesTest`：月区间（含闰年 2 月）、各月天数、已过天数 ≥ 1。
- `AppLaunchTest`（Robolectric，8 例）：**真实启动 `MainActivity`**（create→resume 不崩溃）、Application/容器单例、Room 账户与账单读写、**删除账户后账单仍存在**、DataStore 配置持久化、URL 归一化、模型列表解析。
- `ComposeUiTest`（Robolectric，1 例）：真实 Activity 渲染底部四个 Tab。
- `ScreenRenderTest`（Compose，3 例）：首页渲染输入框/「去配置 AI」/空态、点击「手动记账」回调、解析中状态、账户页空态。
- `QuickEntryTest`（Robolectric + 伪造 HTTP，3 例）：**解析成功直接入账且余额同步**、撤销后账单与余额一并还原、解析失败降级手动表单且输入不丢、空输入不发起请求。
- `AppLaunchTest` 中的账本用例（2 例）：支出/收入/改金额/删除账单后余额逐步核对；编辑账单换账户时两个账户余额同时正确。

- `AccountMatcherTest`（10 例）：卡1/卡2 精确匹配、中文数字「卡二」、卡1 vs 卡12、
  模型返回泛称「银行卡」时按原话纠正、歧义时不乱猜、招行/建行简称匹配。
- `SyncConfigTest`（5 例）：https 才允许同步、localhost 调试例外、地址归一化。
- `SyncBalanceFormatTest`（7 例）：分↔元换算（两位小数、往返一致）、账户 `balance` 与账单 `amount` 字段名与单位。
- `AccountBalanceSyncTest`（4 例）：**记账/删除都会刷新 updatedAt**（否则余额永远不上传）、余额不再是 null、余额等于账单净额。
- `SwitchDeviceSyncTest`（9 例）：**重装 App 后能拉回云端数据**（不被预置空账户误导）、50 元账单在云端仍是 50、
  多档金额不丢精度、换机拉全、首次上传、默认账户合并、分页游标、两端都有数据时按时间戳判断。

### 换新手机的恢复流程

| 步骤 | 操作 | 预期 |
| --- | --- | --- |
| 1 | 旧手机：设置 → 云同步 → 立即同步 | 「已上传到云端」 |
| 2 | 新手机：装 App（不用先记账）→ 填同一地址+密钥 → 立即同步 | 「已从云端拉取」 |
| 3 | 检查账单列表与账户余额 | 数据完整、账户关联正确 |

判断规则：**本地空 + 云端有数据 → 强制拉取**；本地有 + 云端空 → 强制上传；
两端都有 → 对比 `MAX(updatedAt)` 取较新一端。详见 [`server/README.md`](server/README.md)。
- `MigrationTest`（1 例）：v1 → v2 迁移后老数据完整、uid 补齐、deleted 默认 0。
- `QuickEntryTest.secondCardIsCreditedNotFirstCard`：端到端验证「卡2消费2元」余额只扣卡2。

> 说明：release 变体（R8 混淆后）的 Robolectric 全量测试在本机 1.6GB 内存下会超时，
> 因此改为静态验证：`mapping.txt` 与 dex 包列表确认 `EoriDatabase_Impl`、`AccountDao_Impl`、
> `MainActivity` 等关键类均被正确保留，APK 签名与清单校验通过。

---

## 5. 已知限制

1. **未接入真实 AI 服务做端到端联调**：构建环境无可用 API Key，解析链路的网络部分以单元测试 + 错误分类覆盖，实际效果取决于所选模型质量（建议 `gpt-4o-mini`、`deepseek-chat`、`qwen-plus` 等指令跟随较好的模型）。
2. **未在真机/模拟器上运行**：本机构建环境无 KVM，无法启动 Android 模拟器。已用 **Robolectric 在 JVM 上真实启动 Activity 并渲染 Compose 首屏**（33 项测试全绿），并验证 APK 签名/清单/权限正确；但触摸手感、键盘弹出时序、真机字体缩放等仍需安装后目测。
3. **云同步为单用户设计**：一套 Worker + D1 只服务一个人（靠密钥隔离），不支持多人共用一个后端。
4. **同步不做自动触发**：按需求为纯手动，换设备后需手动点一次。
5. **账户余额以「0」为起点累加**：如果建账户时没填余额，第一次记账会从 0 开始算（可能显示负数）。想让余额准确，请在「账户」里填一次当前余额，之后自动增减。
6. **撤销只针对最近一笔自动入账**：Snackbar 消失后仍可在账单列表里删除或编辑。
7. **本地无自动备份**：`allowBackup=false`，卸载即丢失；建议定期手动同步到云端。
8. **无多币种**：金额统一按人民币「元」处理，不识别美元/欧元等。
9. **不支持转账、分期、多笔合并解析**：一句话只解析出一笔账单。
10. **`response_format: json_object` 为可选字段**：部分非标准服务可能忽略或报错，若遇到可改为纯提示词约束（提示词已要求只输出 JSON）。
11. **统计仅支持按月切换**，无年度/自定义区间；趋势图为按日，未做按类别占比。
12. **明暗主题**：仅「跟随系统 + 手动深色」二态，未提供跟随系统/浅色/深色三态。
13. **API Key / 同步密钥以明文存于 DataStore**（沙箱内，非系统级加密）；如需更强保护可接入 `EncryptedSharedPreferences` 或 Keystore 加密，本次未引入以保持依赖精简。

---

## 6. 验收自查

| 验收项 | 状态 | 说明 |
| --- | --- | --- |
| 安装后可直接启动，首页即可输入 | ✅ | 单 Activity + SplashScreen，输入框自动聚焦 |
| 配置合法 Base URL/Key 后能验证并选模型 | ✅ | `GET /models`，自动补 `/v1`，模型下拉 |
| 示例句能解析出金额与收支并保存 | ✅ | 回车即解析、解析即入账（带撤销）；未配置/失败时降级手动 |
| API 不可用时能手动记账，不崩溃 | ✅ | 失败分类降级手动表单，网络异常全捕获 |
| 账户可增删改；统计四项指标与图表正确 | ✅ | CRUD + 总余额 + 余额随账单联动；`净支出÷已过天数`，天数 ≥ 1 |
| 冷启动无明显卡顿，旋转/后台不丢状态 | ✅ | 懒加载 + Activity 级 ViewModel + `rememberSaveable` |
| 仅网络权限 | ✅ | 清单中仅 `INTERNET`（另有 AGP 自动注入的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，非敏感） |
| 国内镜像 | ✅ | 腾讯云 Gradle + 阿里云 Maven |

---

## 7. 目录速查

| 需求 | 文件 |
| --- | --- |
| 快速记账交互 | `ui/home/HomeScreen.kt`、`ui/home/DraftCard.kt` |
| 解析状态机与降级 | `ui/home/HomeViewModel.kt` |
| 提示词 | `data/remote/Prompts.kt` |
| URL 归一化 / JSON 容错 | `data/remote/ApiUrls.kt` |
| 网络调用与校验 | `data/remote/AiClient.kt` |
| 统计指标与图表 | `ui/stats/StatsViewModel.kt`、`ui/stats/StatsScreen.kt` |
| 账户与「已删除账户」 | `ui/accounts/`、`ui/components/Common.kt` |
| AI 配置验证 | `ui/settings/SettingsViewModel.kt` |
| 金额/日期工具 | `util/Formatters.kt` |
