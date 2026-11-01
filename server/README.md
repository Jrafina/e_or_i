# e or i · 云同步部署指南（Cloudflare Workers + D1）

> 目标：让你在 10 分钟内拥有一个**只有你能访问**的同步后端。
> 全部使用 Cloudflare 免费额度：D1 免费 5GB 存储、每天 500 万次读，个人记账远远用不完。

---

## 为什么需要 Worker（不能直连 D1）

Cloudflare D1 **没有对外开放的 HTTP 接口**，只能通过 Worker 访问。
所以架构是：

```
手机 App ──HTTPS + Bearer Token──> Worker（鉴权、限流） ──> D1 数据库
```

这样也顺带解决了安全：D1 数据库本身不对公网暴露，App 只接触 Worker 的 HTTPS 地址。

---

## 一、准备

```bash
# 1. 安装 Node.js 18+ 后
npm install -g wrangler

# 2. 登录 Cloudflare（会打开浏览器授权）
wrangler login
```

---

## 二、创建数据库

```bash
cd server
wrangler d1 create eori-db
```

输出类似：

```
✅ Successfully created DB 'eori-db'
database_id = "a1b2c3d4-5e6f-7890-abcd-ef1234567890"
```

**把这段 `database_id` 复制到 `server/wrangler.toml` 里**：

```toml
[[d1_databases]]
binding = "DB"
database_name = "eori-db"
database_id = "a1b2c3d4-5e6f-7890-abcd-ef1234567890"   # ← 换成你的
```

---

## 三、建表

```bash
wrangler d1 execute eori-db --remote --file=./schema.sql
```

看到 `Executed 6 commands` 之类即成功。

---

## 四、设置同步密钥

**这是最重要的一步**：密钥决定谁能读写你的账单。

```bash
# 生成一个 32 位随机密钥（在手机上也要用它）
openssl rand -hex 32
# 例：9f2c7a1e5b8d4c3a6f0e9d8c7b6a5f4e3d2c1b0a9f8e7d6c5b4a3f2e1d0c9b8a

wrangler secret put SYNC_TOKEN
# 粘贴上面的密钥，回车
```

> ⚠️ 要求：
> - **长度至少 16 位**，代码里做了强制校验，太短会直接拒绝服务；
> - 不要用 `123456`、`password` 这类弱口令；
> - 密钥**只保存在手机本地**和 Cloudflare Secret 里，不会进 Git、不会打在 APK 里。

---

## 五、部署

```bash
wrangler deploy
```

输出：

```
Uploaded eori-sync
Published eori-sync (0.42 sec)
  https://eori-sync.你的子域.workers.dev
```

这个 `https://...workers.dev` 地址就是 App 里要填的**同步地址**。

自检一下：

```bash
curl https://eori-sync.你的子域.workers.dev/health
# 无密钥访问应返回 {"error":"unauthorized"} —— 说明鉴权生效

curl -H "Authorization: Bearer 你的密钥" \
     https://eori-sync.你的子域.workers.dev/sync/meta
# 返回 {"lastUpdatedAt":0,"transactionCount":0,...} —— 说明密钥正确
```

---

## 五点五、协议版本（必读）

Worker 里有一个 `PROTOCOL_VERSION` 常量，当前为 **2**：

| 版本 | 金额单位 | 说明 |
| --- | --- | --- |
| 1 | 分（整数） | 旧版 |
| **2** | **元（两位小数）** | 当前 |

App 每次请求都会带 `X-Eori-Protocol: 2`，服务端校验不一致就返回 **409**。

**为什么需要这个**：如果 App 是新的（发"元"）、Worker 是旧的（又除一次 100），
50 元就会被存成 0.5 元。这种"静默写错数据"比直接报错危险得多。
有了版本校验，两者不匹配时会明确报错：

```
同步协议版本不匹配。
1) 云端 Worker 已重新执行 wrangler deploy（必须是含 PROTOCOL_VERSION=2 的版本）
2) App 为最新版本
```

> ⚠️ **改了 `worker.js` 但没有 `wrangler deploy`，线上跑的还是旧代码。**
> 这是最容易忽略的一步，每次改完服务端都必须重新部署。

自检命令：

```bash
curl https://你的地址.workers.dev/health
# {"ok":true,"service":"eori-sync","protocolVersion":2,...}
```

如果 `protocolVersion` 不是 2，说明没部署成功。

---

## 六、在 App 里配置

打开 App → **设置** → **云同步**：

| 字段 | 填什么 |
| --- | --- |
| 同步地址 | `https://eori-sync.你的子域.workers.dev` |
| 同步密钥 | 第四步生成的密钥 |

先点 **测试连接**（只读，不改数据），成功后再点 **立即同步**。

设置页始终显示 **最近同步时间**；从未同步过时显示「从未同步」。

---

## 七、同步是怎么工作的

### 方向判断（按两端最后时间戳）

```
GET /sync/meta  →  { lastUpdatedAt: 1712345678901 }
本地            →  MAX(updatedAt) = 1712345000000
```

| 情况 | 动作 |
| --- | --- |
| 云端时间戳更大 | **拉取（PULL）**：只下载云端比本地新的记录 |
| 本地时间戳更大 | **上传（PUSH）**：只上传本地比云端新的记录 |
| 相差 < 1 秒 | **已是最新**，不传任何数据 |

### 增量，不整库覆盖

- 1000 条账单里改了 3 条 → **只传这 3 条**；
- 每批 200 条分页，避免单次请求过大；
- 判断方向时只发一个 `meta` 请求（几十字节），不传数据。

### 删除同步

删除账单时不是物理删除，而是打上 `deleted = 1` 标记：

- 本地和云端都保留这条记录，只是标记为已删除；
- 下一次同步把这个标记传过去，另一端也跟着删除；
- **避免**「手机删了，下次同步又从云端复活」的经典问题。

### 冲突处理

同一条记录两端都改过时，**`updatedAt` 较新的胜出**（服务端 SQL 里用
`WHERE excluded.updatedAt >= updatedAt` 保证只会被更新的版本覆盖）。

---

## 七点五、数据结构说明

D1 中 `accounts` 表的余额字段：

| 字段 | 类型 | 单位 | 说明 |
| --- | --- | --- | --- |
| `balance` | REAL | **元** | 保留两位小数，例如 `1234.56`；未设置时为 `NULL` |
| `amount` | REAL | **元** | 账单金额，保留两位小数，例如 `20.5` |

App 内部仍以「分」（Long）计算以保证精度，**只在同步传输时换算成元**：

- 上传：`123456` 分 → `1234.56` 元
- 下载：`1234.56` 元 → `123456` 分
- 换算是 BigDecimal 精确换算，不会出现 `10.990000000000002`

> 账单金额与账户余额**都**以「元」存储。App 内部仍用「分」计算，保证累加无误差。

### 从旧版升级（重要）

旧版字段是 `balanceCents` / `amountCents`（整数分）。如果你已经部署过，**必须重建两张表**：

```bash
cd server
wrangler d1 execute eori-db --remote --command="DROP TABLE IF EXISTS accounts;"
wrangler d1 execute eori-db --remote --command="DROP TABLE IF EXISTS transactions;"
wrangler d1 execute eori-db --remote --file=./schema.sql
wrangler deploy
```

清空后，**在旧手机上点一次「立即同步」把数据重新上传**，新手机再同步即可拉全。

> 之所以要重建：SQLite 的列类型/单位变了，旧数据是"分"的新结构要求是"元"，
> 混在一起会导致金额放大 100 倍。重建最干净。

---

## 七点六、换新手机怎么恢复？（重点）

这是最容易踩坑的场景，已经专门处理：

### 旧手机 -> 云端

1. 旧手机：设置 → 云同步 → 填地址和密钥 → **立即同步**
2. 看到「已上传到云端 · 账户 N 个 · 账单 M 笔」即成功

### 云端 -> 新手机

1. 新手机安装 App（**不用先记账**）
2. 设置 → 云同步 → 填**同一个**地址和密钥 → **立即同步**
3. 看到「已从云端拉取 · 账户 N 个 · 账单 M 笔」

### 为什么新手机能正确拉全？

旧版有一个致命缺陷：新手机本地为空时，两端时间戳都是 0，会被判定成「已是最新」而什么都不拉。

现在的方向判断：

| 本地 | 云端 | 动作 |
| --- | --- | --- |
| 空 | 有数据 | **强制拉取**（换机恢复） |
| 有数据 | 空 | **强制上传**（首次备份） |
| 空 | 空 | 已是最新 |
| 有数据 | 有数据 | 比较 `MAX(updatedAt)`，较新的一端为准 |

另外还处理了两个换机细节：

- **默认账户合并**：新手机启动会预置「支付宝/微信/银行卡/现金」，
  同步时按账户名与云端账户合并，不会出现两套同名账户；
- **分页不漏数据**：账单超过 200 条时按 `(updatedAt, uid)` 复合游标翻页，
  同一毫秒写入的多条记录也不会被跳过。

### 同步后余额对不对？

余额以同步时刻为准：新手机拿到的是云端的余额数值，
与云端账单净额一致。如果发现对不上，在账户页手动改一次余额即可。

---

## 八、安全要点

| 风险 | 防护 |
| --- | --- |
| 明文传输被窃听 | App **强制 https**，填 `http://` 直接拒绝同步 |
| 地址泄露被人读写 | 每次请求校验 `Bearer Token`，恒定时间比较防时序攻击 |
| 暴力猜密钥 | 每 IP 每分钟 60 次限流 |
| 密钥泄漏到 Git | 密钥只存在 Cloudflare Secret + 手机 DataStore |
| D1 被公网直连 | D1 本身无公网端点，只有 Worker 能访问 |
| 越权看到别人数据 | 单用户设计：一个部署服务一个用户，数据库里不区分租户 |

> 如果你要多人共用一套后端，需要再加 `user_id` 分区，当前设计不覆盖这个场景。

---

## 九、常见问题

**Q：`wrangler d1 create` 报权限错误？**
A：确认 `wrangler whoami` 能看到你的账号，并已 `wrangler login`。

**Q：App 提示「密钥不正确（401）」？**
A：`wrangler secret put SYNC_TOKEN` 之后**必须重新 `wrangler deploy`** 才会生效；另外检查密钥首尾有没有多余空格。

**Q：提示「云端服务异常（500）」？**
A：多数是 `wrangler.toml` 里 `database_id` 没改，或没执行建表 SQL。用
`wrangler tail` 看实时日志。

**Q：金额显示成 0.5 / 余额变成 1，少了一百倍？**
A：典型的"重复换算"——旧版 Worker 会把 App 传来的元又除以 100。解决：
`cd server && wrangler deploy`（确认 `/health` 返回 `protocolVersion: 2`）。
另外表里如果已经有错误数据，需要清空后重新同步：

```bash
wrangler d1 execute eori-db --remote --command="DELETE FROM transactions;"
wrangler d1 execute eori-db --remote --command="DELETE FROM accounts;"
```

然后在手机上看一次「立即同步」重新上传正确数据。

**Q：删除 App 重装后，同步没有把云端数据拉回来？**
A：旧版有缺陷——App 启动会预置 4 个空账户（余额 null），它们的 `updatedAt` 是"刚刚"，
全新设备因此被误判成"本地较新"，结果是**上传空数据覆盖云端**。
现在改为按「是否有真实数据（有余额或有账单）」判断，空壳账户不计入。
重装后点同步会走「拉取云端全量」。

**Q：提示「同步地址必须使用 https://」？**
A：这是刻意设计的拦截，明文会泄露账单和密钥。`workers.dev` 默认就是 https，直接复制即可。

**Q：两台手机能同步吗？**
A：可以。两台都填同一个地址+密钥，先同步 A（上传），再同步 B（拉取）。

**Q：想换密钥？**
A：`wrangler secret put SYNC_TOKEN` 写入新值 → `wrangler deploy` → 所有设备在 App 里改成新密钥。

---

## 十、接口速查

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 健康检查 |
| GET | `/sync/meta` | 云端最后更新时间与记录数 |
| GET | `/sync/pull?since=<ms>` | 拉取比 since 新的账户与账单 |
| POST | `/sync/push` | 上传增量（账户 + 账单） |

所有接口都需要 `Authorization: Bearer <SYNC_TOKEN>`。
