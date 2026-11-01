package com.ddl.eori.data.sync

import androidx.room.withTransaction
import com.ddl.eori.data.local.AccountEntity
import com.ddl.eori.data.local.AccountType
import com.ddl.eori.data.local.EoriDatabase
import com.ddl.eori.data.local.TransactionEntity
import com.ddl.eori.data.local.TxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 同步引擎（手动触发，无自动同步）。
 *
 * 流程：
 * 1. `GET /sync/meta` 拿云端的最后更新时间，与本地 `MAX(updatedAt)` 比较；
 * 2. 云端较新 → `pull`：只拉取云端比本地时间戳更新的记录并按 uid 覆盖本地；
 *    本地较新 → `push`：只上传本地比云端时间戳更新的记录；
 * 3. **只传增量**，不整库覆盖，1000 条里改了 3 条就只传 3 条；
 * 4. 删除通过 `deleted` 标记传输，避免已删除的账被另一端“复活”；
 * 5. 冲突（两端都改过同一条）按 `updatedAt` 较大者为准。
 */
class SyncEngine(
    private val db: EoriDatabase,
    private val api: SyncApi,
) {
    private val txDao = db.transactionDao()
    private val accountDao = db.accountDao()
    private val balanceConverter = SyncBalanceConverter()

    companion object {
        /** 单批上传记录数，避免一次请求过大 */
        private const val PAGE_SIZE = 200

        /** 同一毫秒内新增的记录可能被 since 漏掉，判断时留一点余量 */
        private const val CLOCK_SKEW_MS = 1_000L
    }

    /** 仅查询两端时间戳，不传数据——用于“先看谁更新” */
    suspend fun check(config: SyncConfig): Pair<SyncMetaResponse, Long> = withContext(Dispatchers.IO) {
        val meta = api.meta(config)
        meta to localLastUpdatedAt()
    }

    /**
     * 执行一次同步。
     *
     * 方向判断（关键，覆盖“换新手机”场景）：
     * 1. **本地为空、云端有数据** -> 必然 PULL（新手机首次同步最典型的情况）；
     *    这条必须排在最前，否则两端时间戳都为 0 会被误判成“已是最新”，
     *    新手机永远拉不到旧数据。
     * 2. **云端为空、本地有数据** -> PUSH（旧手机首次上传）。
     * 3. 两端都有数据 -> 比较 MAX(updatedAt)，较新的一端为准。
     *
     * @param forceDirection 为空时按上面的规则自动判断
     */
    suspend fun run(
        config: SyncConfig,
        forceDirection: SyncDirection? = null,
    ): SyncOutcome = withContext(Dispatchers.IO) {
        val meta = api.meta(config)
        val localLast = localLastUpdatedAt()

        // 判断“本地是否有真实用户数据”：
        // 不能只看时间戳——App 每次启动都会预置「支付宝/微信/银行卡/现金」4 个空账户，
        // 它们的 updatedAt 是“刚刚”，会让全新的重装设备被误判成“本地较新”，
        // 从而把空数据推上去覆盖云端（表现为：重装后同步，云端数据反而没了）。
        val localTxCount = txDao.visibleCount()
        val localMeaningfulAccounts = accountDao.meaningfulCount()
        val localHasData = localTxCount > 0 || localMeaningfulAccounts > 0

        val cloudHasData = meta.lastUpdatedAt > 0L || meta.transactionCount > 0 || meta.accountCount > 0

        val direction = forceDirection ?: when {
            // 本地无有效数据 + 云端有 -> 无条件拉取（重装/换机恢复）
            !localHasData && cloudHasData -> SyncDirection.PULL
            // 云端空 + 本地有 -> 无条件上传（首次备份）
            localHasData && !cloudHasData -> SyncDirection.PUSH
            // 两端都空
            !localHasData && !cloudHasData -> SyncDirection.UP_TO_DATE
            // 两端都有 -> 谁新听谁的
            meta.lastUpdatedAt > localLast + CLOCK_SKEW_MS -> SyncDirection.PULL
            localLast > meta.lastUpdatedAt + CLOCK_SKEW_MS -> SyncDirection.PUSH
            else -> SyncDirection.UP_TO_DATE
        }

        when (direction) {
            // 本地无有效数据时 since 传 0，确保拿到云端全量
            SyncDirection.PULL -> pull(config, meta, if (localHasData) localLast else 0L)
            SyncDirection.PUSH -> push(config)
            SyncDirection.UP_TO_DATE -> SyncOutcome(
                direction = SyncDirection.UP_TO_DATE,
                accountsSynced = 0,
                transactionsSynced = 0,
                finishedAt = System.currentTimeMillis(),
            )
        }
    }

    // ---------------------------------------------------------------- 拉取

    private suspend fun pull(
        config: SyncConfig,
        meta: SyncMetaResponse,
        localLast: Long,
    ): SyncOutcome {
        var accountCount = 0
        var txCount = 0

        // 逐页拉取。分页由服务端决定（hasMore + nextSince）：
        // 客户端不猜边界，避免同一毫秒多条记录被跳过。
        var cursor = localLast
        var cursorUid: String? = null
        var guard = 0

        while (guard++ < 500) {
            val page = api.pull(config, cursor, cursorUid)

            accountCount += applyRemoteAccounts(page.accounts)
            txCount += applyRemoteTransactions(page.transactions, page.accounts)

            if (!page.hasMore) break

            // 复合游标 (updatedAt, uid)：同一毫秒的多条记录也能逐页推进。
            // 只要游标严格前进（时间戳变大，或时间戳不变但 uid 变化），就继续翻页。
            val next = page.nextSince ?: break
            val nextUid = page.nextSinceUid
            val progressed = next > cursor || (next == cursor && nextUid != null && nextUid != cursorUid)
            if (!progressed) break
            cursor = next
            cursorUid = nextUid
        }

        return SyncOutcome(
            direction = SyncDirection.PULL,
            accountsSynced = accountCount,
            transactionsSynced = txCount,
            finishedAt = System.currentTimeMillis(),
        )
    }

    /** 云端账户 -> 本地（按 uid upsert） */
    private suspend fun applyRemoteAccounts(items: List<SyncAccountDto>): Int {
        if (items.isEmpty()) return 0
        return db.withTransaction {
            val uids = items.map { it.uid }
            val existing = accountDao.getByUids(uids).associateBy { it.uid }
            var applied = 0
            items.forEach { dto ->
                var local = existing[dto.uid]
                // 换新手机时本地会预置「支付宝/微信/银行卡/现金」等默认账户，
                // 它们与云端账户同名但 uid 不同；这里按同名合并，避免出现两套账户。
                if (local == null) {
                    local = accountDao.getAll().firstOrNull {
                        it.name == dto.name && it.uid != dto.uid
                    }
                }
                // 合并进来的本地账户是同名的“空壳”（新手机预置的默认账户），
                // 即使其 updatedAt 更新，也要用云端数据覆盖，否则换机后余额/uid 都对不上。
                val isMergedShell = local != null && local.uid != dto.uid
                if (local != null && !isMergedShell && local.updatedAt > dto.updatedAt) {
                    return@forEach // 同 uid 且本地更新，保留本地
                }

                // 余额取值：云端有值就用云端；云端为 null 时保留本地已有余额（不要用 null 覆盖）
                val remoteBalance = dto.balance?.let { balanceConverter.yuanToCents(it) }

                val entity = AccountEntity(
                    id = local?.id ?: 0,
                    name = dto.name.ifBlank { "未命名账户" },
                    type = runCatching { AccountType.valueOf(dto.type) }.getOrDefault(AccountType.OTHER),
                    balanceCents = remoteBalance ?: local?.balanceCents,
                    createdAt = dto.createdAt,
                    uid = dto.uid,
                    updatedAt = dto.updatedAt,
                )
                if (local == null) accountDao.insert(entity) else accountDao.update(entity)
                applied++
            }
            applied
        }
    }

    /**
     * 云端账单 -> 本地。
     *
     * 关键：账单里的 accountUid 需要转换成**本地账户 id**，
     * 否则同步过来的账单会关联不上账户（显示成“已删除账户”）。
     */
    private suspend fun applyRemoteTransactions(
        items: List<SyncTransactionDto>,
        remoteAccounts: List<SyncAccountDto>,
    ): Int {
        if (items.isEmpty()) return 0
        return db.withTransaction {
            // 本地账户 uid -> 本地 id
            val uidToLocal = accountDao.getByUids(remoteAccounts.map { it.uid })
                .associate { it.uid to it.id }
            val allLocal = accountDao.getAll().associate { it.uid to it.id }
            val existing = txDao.getByUids(items.map { it.uid }).associateBy { it.uid }

            var applied = 0
            items.forEach { dto ->
                val local = existing[dto.uid]
                // 冲突：本地更新时间更晚则保留本地
                if (local != null && local.updatedAt > dto.updatedAt) return@forEach

                val localAccountId = dto.accountUid?.let { uidToLocal[it] ?: allLocal[it] }
                val entity = TransactionEntity(
                    id = local?.id ?: 0,
                    amountCents = balanceConverter.yuanToCents(dto.amount),
                    type = runCatching { TxType.valueOf(dto.type) }.getOrDefault(TxType.EXPENSE),
                    accountId = localAccountId,
                    accountName = dto.accountName,
                    note = dto.note,
                    rawInput = dto.rawInput,
                    occurredAt = dto.occurredAt,
                    createdAt = dto.createdAt,
                    uid = dto.uid,
                    updatedAt = dto.updatedAt,
                    deleted = dto.deleted,
                )
                if (local == null) txDao.insert(entity) else txDao.update(entity)
                applied++
            }
            applied
        }
    }

    // ---------------------------------------------------------------- 上传

    private suspend fun push(config: SyncConfig): SyncOutcome {
        var accountCount = 0
        var txCount = 0

        // 账户（数量少，一批即可）。
        // 余额为 null 时先按「账单净额」补算，避免云端一直存 NULL。
        val accountRows = accountDao.getSince(0L, PAGE_SIZE).filter { it.uid.isNotBlank() }
        val resolvedAccounts = accountRows.map { row ->
            if (row.balanceCents == null) {
                val net = computeNetForAccount(row.id)
                accountDao.setBalance(row.id, net, System.currentTimeMillis())
                row.copy(balanceCents = net)
            } else {
                row
            }
        }
        val accountDtos = resolvedAccounts.map { it.toDto() }

        // 账单需要 accountUid 才能在另一端关联到账户
        val accountUidById = accountDao.getAll().associate { it.id to it.uid }

        // 账单：按 updatedAt 分页收集（先收集完再统一发送）
        val txDtos = mutableListOf<SyncTransactionDto>()
        var cursor = 0L
        while (true) {
            val batch = txDao.getSince(cursor, PAGE_SIZE).filter { it.uid.isNotBlank() }
            if (batch.isEmpty()) break
            txDtos += batch.map { tx -> tx.toDto(accountUid = tx.accountId?.let { accountUidById[it] }) }
            val nextCursor = batch.maxOf { it.updatedAt }
            if (batch.size < PAGE_SIZE || nextCursor <= cursor) break
            cursor = nextCursor
        }

        // 账户与账单合并成一次请求发送：
        // 分开发会导致账单先到、账户后到，另一端解析 accountUid 时找不到账户。
        if (accountDtos.isNotEmpty()) {
            val resp = api.push(config, SyncPushRequest(accounts = accountDtos))
            accountCount = resp.accepted
        }
        if (txDtos.isNotEmpty()) {
            // 账单分批（避免单请求过大），但每批都附带账户信息，保证跨端关联
            txDtos.chunked(PAGE_SIZE).forEachIndexed { index, chunk ->
                val resp = api.push(
                    config,
                    SyncPushRequest(
                        accounts = if (index == 0) accountDtos else emptyList(),
                        transactions = chunk,
                    )
                )
                txCount += resp.accepted
            }
        }

        return SyncOutcome(
            direction = SyncDirection.PUSH,
            accountsSynced = accountCount,
            transactionsSynced = txCount,
            finishedAt = System.currentTimeMillis(),
        )
    }

    /** 某账户的账单净额（收入 - 支出，单位：分） */
    private suspend fun computeNetForAccount(accountId: Long): Long {
        val totals = txDao.observeTotalsByAccount(accountId).first()
        return totals.incomeCents - totals.expenseCents
    }

    private suspend fun localLastUpdatedAt(): Long {
        val tx = txDao.lastUpdatedAt()
        val acc = accountDao.lastUpdatedAt()
        return maxOf(tx, acc)
    }

    private fun AccountEntity.toDto() = SyncAccountDto(
        uid = uid.ifBlank { UUID.randomUUID().toString() },
        name = name,
        type = type.name,
        balance = balanceCents?.let { balanceConverter.centsToYuan(it) },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun TransactionEntity.toDto(accountUid: String?) = SyncTransactionDto(
        uid = uid,
        amount = balanceConverter.centsToYuan(amountCents),
        type = type.name,
        accountUid = accountUid,
        accountName = accountName,
        note = note,
        rawInput = rawInput,
        occurredAt = occurredAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deleted = deleted,
    )
}
