/**
 * e or i 云同步 Worker（Cloudflare Workers + D1）
 *
 * 接口：
 *   GET  /sync/meta              -> 云端最后更新时间与记录数
 *   GET  /sync/pull?since=<ms>   -> 云端比 since 更新的账户与账单
 *   POST /sync/push              -> 上传增量，按 uid 覆盖
 *
 * 安全：
 *   - 全部走 HTTPS（workers.dev 自带证书）
 *   - 每个请求必须带 Authorization: Bearer <SYNC_TOKEN>
 *   - 令牌比较使用恒定时间算法，避免时序侧信道
 *   - 简单的按 IP 限流，防止暴力猜测
 */

const PAGE_SIZE = 200
const RATE_LIMIT_PER_MINUTE = 60

/**
 * 协议版本。金额单位的定义变了就必须升版本：
 *   v1 = 分（整数）
 *   v2 = 元（两位小数）  <- 当前
 * App 会带上自己期望的版本，不匹配时直接报错，
 * 避免“新旧混用”导致金额被重复换算（50 元变 0.5 元）。
 */
const PROTOCOL_VERSION = 2

export default {
  async fetch(request, env) {
    const url = new URL(request.url)

    // 统一加安全响应头
    const baseHeaders = {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    }

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: baseHeaders })
    }

    try {
      // ---- 协议版本校验（先于鉴权，确保旧版 App 得到明确错误）----
      const versionError = checkProtocol(request)
      if (versionError) return json({ error: versionError.message, protocolVersion: PROTOCOL_VERSION }, 409, baseHeaders)

      // ---- 鉴权 ----
      const authError = checkAuth(request, env)
      if (authError) return json({ error: authError.message }, authError.status, baseHeaders)

      const rate = await checkRateLimit(request, env)
      if (!rate.ok) return json({ error: rate.message }, 429, baseHeaders)

      // ---- 路由 ----
      switch (`${request.method} ${url.pathname}`) {
        case 'GET /sync/meta':
          return await handleMeta(env, baseHeaders)
        case 'GET /sync/pull':
          return await handlePull(url, env, baseHeaders)
        case 'POST /sync/push':
          return await handlePush(request, env, baseHeaders)
        case 'GET /':
        case 'GET /health':
          return json(
            { ok: true, service: 'eori-sync', protocolVersion: PROTOCOL_VERSION, time: Date.now() },
            200,
            baseHeaders
          )
        default:
          return json({ error: 'not found' }, 404, baseHeaders)
      }
    } catch (e) {
      // 不把内部细节抛给客户端，只记录日志
      console.error('sync error', e)
      return json({ error: 'internal error' }, 500, baseHeaders)
    }
  },
}

// ---------------------------------------------------------------- 协议与鉴权

/**
 * 校验协议版本。
 * App 通过 X-Eori-Protocol 头声明期望版本；缺失视为 v1（旧客户端）。
 * 版本不一致返回 409，让 App 明确提示“请更新”，而不是写入错误金额。
 */
function checkProtocol(request) {
  const raw = request.headers.get('X-Eori-Protocol')
  if (raw === null) {
    // 旧 App 没带这个头：它发的金额是“分”，与当前 v2（元）不兼容
    return {
      status: 409,
      message: 'protocol mismatch: client is outdated (expected v' + PROTOCOL_VERSION + ')',
    }
  }
  const client = Number(raw)
  if (!Number.isInteger(client) || client !== PROTOCOL_VERSION) {
    return {
      status: 409,
      message: 'protocol mismatch: client v' + raw + ', server v' + PROTOCOL_VERSION,
    }
  }
  return null
}

// ---------------------------------------------------------------- 鉴权与限流

function checkAuth(request, env) {
  if (!env.SYNC_TOKEN || env.SYNC_TOKEN.length < 16) {
    // 密钥太短说明没认真配置，直接拒绝，避免部署成“裸奔”接口
    return { status: 500, message: 'server misconfigured' }
  }
  const header = request.headers.get('Authorization') || ''
  const match = header.match(/^Bearer\s+(.+)$/i)
  if (!match) return { status: 401, message: 'unauthorized' }
  if (!timingSafeEqual(match[1].trim(), env.SYNC_TOKEN)) {
    return { status: 401, message: 'unauthorized' }
  }
  return null
}

/** 恒定时间字符串比较 */
function timingSafeEqual(a, b) {
  const enc = new TextEncoder()
  const x = enc.encode(a)
  const y = enc.encode(b)
  if (x.length !== y.length) return false
  let diff = 0
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i]
  return diff === 0
}

/** 基于内存的简易限流（Worker 实例级，够用且不增加依赖） */
const rateBuckets = new Map()
async function checkRateLimit(request, env) {
  const key = request.headers.get('CF-Connecting-IP') || 'unknown'
  const now = Date.now()
  const bucket = rateBuckets.get(key)
  if (!bucket || now - bucket.start > 60_000) {
    rateBuckets.set(key, { start: now, count: 1 })
    return { ok: true }
  }
  bucket.count++
  if (bucket.count > RATE_LIMIT_PER_MINUTE) {
    return { ok: false, message: 'too many requests' }
  }
  return { ok: true }
}

// ---------------------------------------------------------------- 业务处理

async function handleMeta(env, headers) {
  const tx = await env.DB.prepare(
    'SELECT COUNT(*) AS c, IFNULL(MAX(updatedAt), 0) AS m FROM transactions'
  ).first()
  const acc = await env.DB.prepare(
    'SELECT COUNT(*) AS c, IFNULL(MAX(updatedAt), 0) AS m FROM accounts'
  ).first()

  return json(
    {
      serverTime: Date.now(),
      lastUpdatedAt: Math.max(tx?.m || 0, acc?.m || 0),
      transactionCount: tx?.c || 0,
      accountCount: acc?.c || 0,
    },
    200,
    headers
  )
}

async function handlePull(url, env, headers) {
  const since = Number(url.searchParams.get('since') || 0) || 0
  const sinceUid = url.searchParams.get('sinceUid') || ''

  // 多取一条，用来判断是否还有下一页（避免客户端猜边界）
  // 账户数量少，一次全取（不走分页）
  const accounts = await env.DB.prepare(
    'SELECT * FROM accounts WHERE updatedAt > ? ORDER BY updatedAt ASC, uid ASC LIMIT 1000'
  ).bind(since).all()

  // 复合游标 (sinceTs, sinceUid)：同一毫秒的多条记录也能正确分页，不重不漏
  const transactions = await env.DB.prepare(
    `SELECT * FROM transactions
     WHERE updatedAt > ? OR (updatedAt = ? AND uid > ?)
     ORDER BY updatedAt ASC, uid ASC LIMIT ?`
  ).bind(since, since, sinceUid, PAGE_SIZE + 1).all()

  const accRows = accounts.results || []
  const txRows = transactions.results || []

  const txHasMore = txRows.length > PAGE_SIZE
  if (txHasMore) txRows.length = PAGE_SIZE

  const hasMore = txHasMore

  // 下一页游标：
  // 账户数量少（通常 <20），一页就能取完；真正会分页的是 transactions。
  // 所以优先按 transactions 推进；若账户还有剩余而交易已取完，则按账户推进。
  // 游标用「本页最后一条的 updatedAt + 1」，避免同一毫秒的记录被无限重复返回。
  const last = txRows[txRows.length - 1]
  const nextSince = hasMore && last ? last.updatedAt : null
  const nextSinceUid = hasMore && last ? last.uid : null

  const lastUpdatedAt = [...accRows, ...txRows].reduce((m, r) => Math.max(m, r.updatedAt || 0), since)

  return json(
    {
      serverTime: Date.now(),
      lastUpdatedAt,
      accounts: accRows.map(rowToAccount),
      transactions: txRows.map(rowToTx),
      hasMore,
      nextSince,
      nextSinceUid,
    },
    200,
    headers
  )
}

async function handlePush(request, env, headers) {
  const body = await request.json()
  const accounts = Array.isArray(body.accounts) ? body.accounts : []
  const txs = Array.isArray(body.transactions) ? body.transactions : []
  if (accounts.length + txs.length > 1000) {
    return json({ error: 'batch too large' }, 413, headers)
  }

  const statements = []

  for (const a of accounts) {
    if (!validUid(a.uid)) continue
    statements.push(
      env.DB.prepare(
        `INSERT INTO accounts (uid, name, type, balance, createdAt, updatedAt)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(uid) DO UPDATE SET
           name = excluded.name, type = excluded.type, balance = excluded.balance,
           createdAt = excluded.createdAt, updatedAt = excluded.updatedAt
         WHERE excluded.updatedAt >= accounts.updatedAt`
      ).bind(
        a.uid, String(a.name || ''), String(a.type || 'OTHER'),
        toYuan(a.balance), Number(a.createdAt) || Date.now(), Number(a.updatedAt) || Date.now()
      )
    )
  }

  for (const t of txs) {
    if (!validUid(t.uid)) continue
    statements.push(
      env.DB.prepare(
        `INSERT INTO transactions
           (uid, amount, type, accountUid, accountName, note, rawInput, occurredAt, createdAt, updatedAt, deleted)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(uid) DO UPDATE SET
           amount = excluded.amount, type = excluded.type, accountUid = excluded.accountUid,
           accountName = excluded.accountName, note = excluded.note, rawInput = excluded.rawInput,
           occurredAt = excluded.occurredAt, createdAt = excluded.createdAt,
           updatedAt = excluded.updatedAt, deleted = excluded.deleted
         WHERE excluded.updatedAt >= transactions.updatedAt`
      ).bind(
        t.uid, toYuan(t.amount) ?? 0, String(t.type || 'EXPENSE'),
        t.accountUid ?? null, t.accountName ?? null,
        String(t.note || ''), String(t.rawInput || ''),
        Number(t.occurredAt) || Date.now(), Number(t.createdAt) || Date.now(),
        Number(t.updatedAt) || Date.now(), t.deleted ? 1 : 0
      )
    )
  }

  if (statements.length > 0) await env.DB.batch(statements)

  const latest = await env.DB.prepare(
    'SELECT IFNULL(MAX(m), 0) AS m FROM (SELECT MAX(updatedAt) AS m FROM transactions UNION ALL SELECT MAX(updatedAt) FROM accounts)'
  ).first()

  return json(
    { ok: true, accepted: statements.length, serverTime: Date.now(), lastUpdatedAt: latest?.m || 0 },
    200,
    headers
  )
}

// ---------------------------------------------------------------- 工具

/**
 * 金额标准化：传输与存储统一使用「元」，保留两位小数。
 *
 * 注意：App 端已经完成「分 -> 元」换算，这里**只能**做四舍五入到两位小数，
 * 绝不能再除以 100，否则会出现 50 元被记成 0.5 元的重复换算错误。
 */
function toYuan(value) {
  if (value === null || value === undefined) return null
  const n = Number(value)
  if (!Number.isFinite(n)) return null
  return Math.round(n * 100) / 100
}

function validUid(uid) {
  return typeof uid === 'string' && uid.length > 0 && uid.length <= 64
}

function rowToAccount(r) {
  return {
    uid: r.uid,
    name: r.name,
    type: r.type,
    balance: toYuan(r.balance),
    createdAt: Number(r.createdAt),
    updatedAt: Number(r.updatedAt),
  }
}

function rowToTx(r) {
  return {
    uid: r.uid,
    amount: toYuan(r.amount),
    type: r.type,
    accountUid: r.accountUid ?? null,
    accountName: r.accountName ?? null,
    note: r.note || '',
    rawInput: r.rawInput || '',
    occurredAt: Number(r.occurredAt),
    createdAt: Number(r.createdAt),
    updatedAt: Number(r.updatedAt),
    deleted: !!r.deleted,
  }
}

function json(obj, status, headers) {
  return new Response(JSON.stringify(obj), { status, headers })
}
