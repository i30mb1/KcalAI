package n7.kcalai.feature.diary

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import n7.kcalai.fooddb.FoodDb
import n7.kcalai.fooddb.FoodDbFactory

/**
 * Справочник, который открывается, но в котором нет ни строки: любой запрос
 * отвечает пустотой. Нужен там, где экран должен дойти мимо справочника до сети.
 */
internal fun emptySeed(): FoodDb = FoodDbFactory.open(EmptyDriver, seedPath = "empty", foodPath = null)

private object EmptyDriver : SQLiteDriver {
    override fun open(fileName: String): SQLiteConnection = EmptyConnection
}

private object EmptyConnection : SQLiteConnection {
    override fun prepare(sql: String): SQLiteStatement = EmptyStatement()
    override fun close() = Unit
}

private class EmptyStatement : SQLiteStatement {
    override fun bindBlob(index: Int, value: ByteArray) = Unit
    override fun bindDouble(index: Int, value: Double) = Unit
    override fun bindLong(index: Int, value: Long) = Unit
    override fun bindText(index: Int, value: String) = Unit
    override fun bindNull(index: Int) = Unit
    override fun getBlob(index: Int): ByteArray = error("строк нет")
    override fun getDouble(index: Int): Double = error("строк нет")
    override fun getLong(index: Int): Long = error("строк нет")
    override fun getText(index: Int): String = error("строк нет")
    override fun isNull(index: Int): Boolean = error("строк нет")
    override fun getColumnCount(): Int = 0
    override fun getColumnName(index: Int): String = error("колонок нет")
    override fun getColumnType(index: Int): Int = error("колонок нет")
    override fun step(): Boolean = false
    override fun reset() = Unit
    override fun clearBindings() = Unit
    override fun close() = Unit
}
