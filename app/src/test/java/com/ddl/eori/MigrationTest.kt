package com.ddl.eori

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ddl.eori.data.local.EoriDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 数据库迁移测试：v1（无同步字段） -> v2（含 uid / updatedAt / deleted）。
 *
 * 升级时最容易丢数据的环节，必须验证：
 * 1. 老数据完整保留；
 * 2. 每条记录都补上了非空 uid 与 updatedAt；
 * 3. 老账单 deleted = 0，仍会出现在列表里。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationTest {

    private fun buildV1(name: String): android.database.sqlite.SQLiteDatabase =
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getDatabasePath(name).also { it.parentFile?.mkdirs() },
            null,
        ).apply {
            execSQL(
                "CREATE TABLE IF NOT EXISTS accounts (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, " +
                    "balanceCents INTEGER, createdAt INTEGER NOT NULL)"
            )
            execSQL(
                "CREATE TABLE IF NOT EXISTS transactions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, amountCents INTEGER NOT NULL, type TEXT NOT NULL, " +
                    "accountId INTEGER, accountName TEXT, note TEXT NOT NULL, rawInput TEXT NOT NULL, " +
                    "occurredAt INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
            )
            execSQL("CREATE INDEX IF NOT EXISTS index_transactions_occurredAt ON transactions (occurredAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_transactions_accountId ON transactions (accountId)")
        }

    @Test
    fun migrate1To2KeepsDataAndBackfillsUid() {
        val name = "migration-test.db"
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(name)

        // 1) 造一个 v1 库并写入数据
        val v1 = buildV1(name)
        val now = 1_700_000_000_000L
        v1.execSQL("INSERT INTO accounts (name, type, balanceCents, createdAt) VALUES ('卡1','BANK',100000,?)", arrayOf<Any>(now))
        v1.execSQL("INSERT INTO accounts (name, type, balanceCents, createdAt) VALUES ('卡2','BANK',50000,?)", arrayOf<Any>(now))
        v1.execSQL(
            "INSERT INTO transactions (amountCents,type,accountId,accountName,note,rawInput,occurredAt,createdAt) " +
                "VALUES (2000,'EXPENSE',2,'卡2','午饭','卡2消费2元',?,?)", arrayOf<Any>(now, now)
        )
        v1.execSQL(
            "INSERT INTO transactions (amountCents,type,accountId,accountName,note,rawInput,occurredAt,createdAt) " +
                "VALUES (300,'INCOME',1,'卡1','红包','卡1收红包0.3',?,?)", arrayOf<Any>(now, now)
        )
        v1.version = 1
        v1.close()

        // 2) 打开 v2 数据库：Room 会执行 MIGRATION_1_2
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EoriDatabase::class.java,
            name,
        ).addMigrations(EoriDatabase.MIGRATION_1_2).build()

        runBlocking {
            val accounts = db.accountDao().getAll()
            assertEquals("老账户应保留", 2, accounts.size)
            assertTrue("账户名保留", accounts.map { it.name }.containsAll(listOf("卡1", "卡2")))
            accounts.forEach {
                assertTrue("账户 uid 应被补齐", it.uid.isNotBlank())
                assertTrue("updatedAt 应被补齐", it.updatedAt > 0)
            }

            val txs = db.transactionDao().getAllForSync(100)
            assertEquals("老账单应保留", 2, txs.size)
            assertTrue(txs.all { it.uid.isNotBlank() })
            assertTrue("老账单默认未删除", txs.none { it.deleted })
            assertTrue(txs.any { it.amountCents == 2_000L && it.accountName == "卡2" })
        }
        db.close()
    }
}
