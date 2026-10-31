package com.ddl.eori.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

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
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class EoriDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun accountDao(): AccountDao

    companion object {
        @Volatile
        private var instance: EoriDatabase? = null

        fun get(context: Context): EoriDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EoriDatabase::class.java,
                    "eori.db",
                ).build().also { instance = it }
            }
    }
}
