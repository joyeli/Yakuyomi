package eu.kanade.tachiyomi.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import tachiyomi.data.Mangas
import tachiyomi.data.MangasQueries
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.view.LibraryViewQueries

/**
 * 書庫轉圈修正（2026-10-06）真的進了打包的 SQL：用一個只記錄 SQL 字串的假 driver 跑 SQLDelight 產生的查詢，
 * 檢查 libraryView.sq 的 `library` 兩個聚合子查詢有先篩書庫本、mangas.sq 的網址查找寫成 `+source`。
 * 結果逐列相同已在桌面用 SQLite 3.50.1 比對過；這裡只守住「改寫沒被誤刪」。
 *
 * mihon v0.21 merge（upstream 93cc07511 重寫 schema，這幾個查詢改用 upstream 的）時連同這個測試一起刪掉。
 */
class LibraryQueryRewriteTest {

    private val mangasAdapter = Mangas.Adapter(
        genreAdapter = StringListColumnAdapter,
        update_strategyAdapter = UpdateStrategyColumnAdapter,
        memoAdapter = MemoColumnAdapter,
    )

    @Test
    fun `library：章節與分類子查詢都先篩書庫本，不再讀整個 libraryView`() {
        val driver = RecordingDriver()
        LibraryViewQueries(driver, mangasAdapter).library().executeAsList()

        driver.sql shouldHaveSize 1
        val sql = driver.sql.single().oneLine()
        sql shouldNotContain "FROM libraryView"
        sql shouldContain
            "AND chapters.manga_id IN (SELECT _id FROM mangas WHERE favorite = 1) GROUP BY chapters.manga_id"
        sql shouldContain
            "FROM mangas_categories WHERE manga_id IN (SELECT _id FROM mangas WHERE favorite = 1) GROUP BY manga_id"
        sql shouldContain "WHERE M.favorite = 1"
    }

    @Test
    fun `getMangaByUrlAndSource：source 前加加號，讓 SQLite 走網址索引`() {
        val driver = RecordingDriver()
        MangasQueries(driver, mangasAdapter).getMangaByUrlAndSource("/m/1", 1L).executeAsOneOrNull()

        driver.sql.single().oneLine() shouldContain "WHERE url = ? AND +source = ? LIMIT 1"
    }

    @Test
    fun `insertNetworkManga：三句網址查找都是 +source`() = runTest {
        val driver = RecordingDriver()
        MangasQueries(driver, mangasAdapter).insertNetworkManga(
            source = 1L,
            url = "/m/1",
            artist = null,
            author = null,
            description = null,
            genre = null,
            title = "t",
            status = 0L,
            thumbnailUrl = null,
            favorite = false,
            lastUpdate = null,
            nextUpdate = null,
            initialized = false,
            viewerFlags = 0L,
            chapterFlags = 0L,
            coverLastModified = 0L,
            dateAdded = 0L,
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            calculateInterval = 0L,
            version = 0L,
            memo = JsonObject(emptyMap()),
            updateTitle = false,
            updateCover = false,
            updateDetails = false,
        ).awaitAsOneOrNull()

        val statements = driver.sql.map { it.oneLine() }
        statements shouldHaveSize 3
        statements[0] shouldContain "WHERE NOT EXISTS(SELECT 0 FROM mangas WHERE +source = ? AND url = ?)"
        statements[1] shouldContain "WHERE +source = ? AND url = ? AND favorite = 0"
        statements[2] shouldContain "WHERE +source = ? AND url = ? LIMIT 1"
        statements.count { "+source = ?" in it } shouldBe 3
    }

    private fun String.oneLine(): String = split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** 只記下收到的 SQL；查詢一律回空結果，交易什麼都不做。 */
    private class RecordingDriver : SqlDriver {
        val sql = mutableListOf<String>()
        private var current: Transacter.Transaction? = null

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            this.sql += sql
            return mapper(EmptyCursor)
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            this.sql += sql
            return QueryResult.Value(0L)
        }

        override fun newTransaction(): QueryResult<Transacter.Transaction> {
            val enclosing = current
            val transaction = object : Transacter.Transaction() {
                override val enclosingTransaction: Transacter.Transaction? = enclosing

                override fun endTransaction(successful: Boolean): QueryResult<Unit> {
                    current = enclosing
                    return QueryResult.Unit
                }
            }
            current = transaction
            return QueryResult.Value(transaction)
        }

        override fun currentTransaction(): Transacter.Transaction? = current

        override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

        override fun notifyListeners(vararg queryKeys: String) = Unit

        override fun close() = Unit
    }

    private object EmptyCursor : SqlCursor {
        override fun next(): QueryResult<Boolean> = QueryResult.Value(false)
        override fun getString(index: Int): String? = null
        override fun getLong(index: Int): Long? = null
        override fun getBytes(index: Int): ByteArray? = null
        override fun getDouble(index: Int): Double? = null
        override fun getBoolean(index: Int): Boolean? = null
    }
}
