package com.ddl.eori.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/** 枚举 <-> 字符串 转换，避免使用 ordinal 造成数据漂移 */
class Converters {
    @TypeConverter
    fun fromTxType(value: TxType): String = value.name

    @TypeConverter
    fun toTxType(value: String): TxType =
        runCatching { TxType.valueOf(value) }.getOrDefault(TxType.EXPENSE)

    @TypeConverter
    fun fromAccountType(value: AccountType): String = value.name

    @TypeConverter
    fun toAccountType(value: String): AccountType =
        runCatching { AccountType.valueOf(value) }.getOrDefault(AccountType.OTHER)
}

@Database(
    entities = [TransactionEntity::class, AccountEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class EoriDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun accountDao(): AccountDao

    companion object {
        @Volatile
        private var instance: EoriDatabase? = null

        /**
         * v1 -> v2：为云同步增加 uid / updatedAt / deleted 三列。
         * 迁移时给历史数据补齐 uid（随机 UUID）与时间戳，保证老用户数据不丢且能参与同步。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()

                db.execSQL("ALTER TABLE accounts ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE accounts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT $now")
                db.query("SELECT id FROM accounts").use { c ->
                    while (c.moveToNext()) {
                        db.execSQL(
                            "UPDATE accounts SET uid = ? WHERE id = ?",
                            arrayOf<Any>(UUID.randomUUID().toString(), c.getLong(0)),
                        )
                    }
                }

                db.execSQL("ALTER TABLE transactions ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT $now")
                db.execSQL("ALTER TABLE transactions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.query("SELECT id FROM transactions").use { c ->
                    while (c.moveToNext()) {
                        db.execSQL(
                            "UPDATE transactions SET uid = ? WHERE id = ?",
                            arrayOf<Any>(UUID.randomUUID().toString(), c.getLong(0)),
                        )
                    }
                }
            }
        }

        fun get(context: Context): EoriDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EoriDatabase::class.java,
                    "eori.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
