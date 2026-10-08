package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.manga.components.ChapterNightStatus
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * 章節列套佇列與本 session 狀態（[withTranslationState]）：結果只看當下的觀察值與每項的磁碟欄位，不看這一項先前套上去的值。
 * 這是兩件事的保證——掃磁碟那條寫入時重套最新觀察值，蓋掉掃描開頭快照算出的舊狀態；刪下載、重新下載清掉 session 集合時，
 * 「已翻」「已有夜讀版」跟著退回磁碟上的狀態。
 */
class ChapterListTranslationStateTest {

    private val manga = Manga.create().copy(id = 1L)

    private fun chapter(id: Long) = Chapter.create().copy(id = id, mangaId = 1L)

    private fun item(
        id: Long,
        downloaded: Boolean = true,
        translatedOnDisk: Boolean = false,
        nightOnDisk: Boolean = false,
        nightUnsupported: Boolean = false,
    ) = ChapterList.Item(
        chapter = chapter(id),
        downloadState = if (downloaded) Download.State.DOWNLOADED else Download.State.NOT_DOWNLOADED,
        downloadProgress = 0,
        translatedOnDisk = translatedOnDisk,
        nightOnDisk = nightOnDisk,
        nightUnsupported = nightUnsupported,
    )

    private fun queued(
        id: Long,
        status: TranslationItem.Status,
        kind: TranslationItem.Kind,
        done: Int = 0,
        total: Int = 0,
    ) = TranslationItem(manga = manga, chapter = chapter(id), status = status, done = done, total = total, kind = kind)

    private fun obs(
        queue: List<TranslationItem> = emptyList(),
        translated: Set<Long> = emptySet(),
        nightDone: Set<Long> = emptySet(),
        nightEnabled: Boolean = true,
    ) = TranslationObservation(queue, translated, nightDone, nightEnabled)

    @Test
    fun `掃描開頭的快照是產生中、寫入時那項已做完離開佇列：以寫入時的觀察值為準`() {
        val night = TranslationItem.Kind.NIGHT
        val stale = listOf(item(1L)).withTranslationState(
            obs(queue = listOf(queued(1L, TranslationItem.Status.TRANSLATING, night, done = 3, total = 10))),
        )
        stale.single().nightStatus shouldBe ChapterNightStatus.RENDERING
        stale.single().nightProgress shouldBe 0.3f

        // 寫入當下：那項已離開佇列、本 session 做完
        val fresh = stale.withTranslationState(obs(nightDone = setOf(1L))).single()
        fresh.nightStatus shouldBe ChapterNightStatus.DONE
        fresh.nightProgress shouldBe null
        fresh.hasNightPages shouldBe true
    }

    @Test
    fun `翻譯項與夜讀項各上各的指示器，離開佇列就清掉`() {
        val queue = listOf(
            queued(1L, TranslationItem.Status.TRANSLATING, TranslationItem.Kind.TRANSLATE, done = 1, total = 4),
            queued(1L, TranslationItem.Status.QUEUE, TranslationItem.Kind.NIGHT),
        )
        val during = listOf(item(1L)).withTranslationState(obs(queue = queue)).single()
        during.translationStatus shouldBe TranslationItem.Status.TRANSLATING
        during.translationProgress shouldBe 25
        during.nightStatus shouldBe ChapterNightStatus.QUEUED

        val after = listOf(during).withTranslationState(obs(translated = setOf(1L))).single()
        after.translationStatus shouldBe null
        after.translationProgress shouldBe 0
        after.isTranslated shouldBe true
        after.nightStatus shouldBe ChapterNightStatus.NONE
    }

    @Test
    fun `session 集合清掉之後退回磁碟上的狀態`() {
        val done = listOf(item(1L), item(2L, translatedOnDisk = true, nightOnDisk = true))
            .withTranslationState(obs(translated = setOf(1L, 2L), nightDone = setOf(1L, 2L)))
        done.map { it.isTranslated } shouldBe listOf(true, true)
        done.map { it.nightStatus } shouldBe listOf(ChapterNightStatus.DONE, ChapterNightStatus.DONE)

        // 刪掉再重新下載（forgetChapterOutputs）：1 是新下載的原圖、2 的磁碟上本來就有
        val forgotten = done.withTranslationState(obs())
        forgotten.map { it.isTranslated } shouldBe listOf(false, true)
        forgotten.map { it.hasNightPages } shouldBe listOf(false, true)
        forgotten.map { it.nightStatus } shouldBe listOf(ChapterNightStatus.NONE, ChapterNightStatus.DONE)
    }

    @Test
    fun `同一個觀察值套幾次結果都一樣`() {
        val o = obs(
            queue = listOf(queued(2L, TranslationItem.Status.ERROR, TranslationItem.Kind.NIGHT)),
            translated = setOf(1L),
            nightDone = setOf(3L),
        )
        val items = listOf(item(1L), item(2L, nightOnDisk = true), item(3L), item(4L, downloaded = false))
        val once = items.withTranslationState(o)
        once.withTranslationState(o) shouldBe once
        once.map { it.nightStatus } shouldBe listOf(
            ChapterNightStatus.NONE,
            ChapterNightStatus.ERROR,
            ChapterNightStatus.DONE,
            ChapterNightStatus.HIDDEN,
        )
    }

    @Test
    fun `做不了夜讀的章（壓縮檔章）不畫夜讀入口，佇列裡有它的項也一樣`() {
        val o = obs(queue = listOf(queued(1L, TranslationItem.Status.ERROR, TranslationItem.Kind.NIGHT)))
        listOf(item(1L, nightUnsupported = true), item(2L, nightUnsupported = true))
            .withTranslationState(o)
            .map { it.nightStatus } shouldBe listOf(ChapterNightStatus.HIDDEN, ChapterNightStatus.HIDDEN)
    }

    @Test
    fun `夜讀總開關關：不畫，但已翻照算`() {
        val r = listOf(item(1L, nightOnDisk = true))
            .withTranslationState(obs(translated = setOf(1L), nightEnabled = false))
            .single()
        r.nightStatus shouldBe ChapterNightStatus.HIDDEN
        r.isTranslated shouldBe true
        r.hasNightPages shouldBe true
    }
}
