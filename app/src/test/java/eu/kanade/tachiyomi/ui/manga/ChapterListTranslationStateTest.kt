package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.manga.components.ChapterNightStatus
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.translation.NightLink
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

    /** 翻譯＋夜讀的合併工作（翻譯項帶合併意圖；[nightActive]＝這一輪正在一併產生夜讀版）。 */
    private fun combinedJob(
        id: Long,
        status: TranslationItem.Status,
        nightActive: Boolean = status == TranslationItem.Status.TRANSLATING,
        done: Int = 0,
        total: Int = 0,
        link: NightLink = NightLink.AUTO,
    ) = TranslationItem(
        manga = manga,
        chapter = chapter(id),
        status = status,
        done = done,
        total = total,
        kind = TranslationItem.Kind.TRANSLATE,
        nightLink = link,
        nightActive = nightActive,
    )

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
    fun `合併工作排隊中或一併產生中：夜讀指示器 COMBINED、不帶進度，翻譯指示器帶狀態與進度`() {
        for (link in listOf(NightLink.AUTO, NightLink.EXPLICIT)) {
            val queued = listOf(item(1L, nightOnDisk = true))
                .withTranslationState(obs(queue = listOf(combinedJob(1L, TranslationItem.Status.QUEUE, link = link))))
                .single()
            queued.nightStatus shouldBe ChapterNightStatus.COMBINED
            queued.nightProgress shouldBe null
            queued.translationStatus shouldBe TranslationItem.Status.QUEUE

            val running = listOf(item(1L))
                .withTranslationState(
                    obs(
                        queue = listOf(
                            combinedJob(1L, TranslationItem.Status.TRANSLATING, done = 6, total = 18, link = link),
                        ),
                    ),
                )
                .single()
            running.nightStatus shouldBe ChapterNightStatus.COMBINED
            running.nightProgress shouldBe null
            running.translationStatus shouldBe TranslationItem.Status.TRANSLATING
            running.translationProgress shouldBe 33
        }
    }

    @Test
    fun `合併工作的章另外有夜讀項：仍是 COMBINED（一個工作只有一個轉圈）`() {
        val queue = listOf(
            combinedJob(1L, TranslationItem.Status.TRANSLATING, done = 2, total = 10),
            queued(1L, TranslationItem.Status.TRANSLATING, TranslationItem.Kind.NIGHT, done = 3, total = 10),
        )
        val r = listOf(item(1L)).withTranslationState(obs(queue = queue)).single()
        r.nightStatus shouldBe ChapterNightStatus.COMBINED
        r.nightProgress shouldBe null
        r.translationProgress shouldBe 20
    }

    @Test
    fun `只翻譯的輪（這一輪沒開夜讀階段）：夜讀指示器照磁碟`() {
        val o = obs(queue = listOf(combinedJob(1L, TranslationItem.Status.TRANSLATING, nightActive = false)))
        listOf(item(1L)).withTranslationState(o).single().nightStatus shouldBe ChapterNightStatus.NONE
        listOf(item(1L, nightOnDisk = true)).withTranslationState(o).single().nightStatus shouldBe
            ChapterNightStatus.DONE
        // 同章另外有夜讀項：照夜讀項
        val withNight = obs(
            queue = listOf(
                combinedJob(1L, TranslationItem.Status.TRANSLATING, nightActive = false),
                queued(1L, TranslationItem.Status.QUEUE, TranslationItem.Kind.NIGHT),
            ),
        )
        listOf(item(1L)).withTranslationState(withNight).single().nightStatus shouldBe ChapterNightStatus.QUEUED
    }

    @Test
    fun `合併工作失敗（ERROR）：夜讀指示器照磁碟，翻譯指示器是失敗`() {
        val o = obs(queue = listOf(combinedJob(1L, TranslationItem.Status.ERROR, nightActive = false)))
        val r = listOf(item(1L, nightOnDisk = true)).withTranslationState(o).single()
        r.nightStatus shouldBe ChapterNightStatus.DONE
        r.translationStatus shouldBe TranslationItem.Status.ERROR
        listOf(item(2L)).withTranslationState(
            obs(queue = listOf(combinedJob(2L, TranslationItem.Status.ERROR, nightActive = false))),
        ).single().nightStatus shouldBe ChapterNightStatus.NONE
    }

    @Test
    fun `合併工作但夜讀總開關關：夜讀指示器不畫`() {
        val o = obs(
            queue = listOf(combinedJob(1L, TranslationItem.Status.QUEUE)),
            nightEnabled = false,
        )
        val r = listOf(item(1L)).withTranslationState(o).single()
        r.nightStatus shouldBe ChapterNightStatus.HIDDEN
        r.translationStatus shouldBe TranslationItem.Status.QUEUE
    }

    @Test
    fun `沒有合併意圖的翻譯項：夜讀指示器照磁碟`() {
        val o = obs(queue = listOf(combinedJob(1L, TranslationItem.Status.QUEUE, link = NightLink.NONE)))
        listOf(item(1L)).withTranslationState(o).single().nightStatus shouldBe ChapterNightStatus.NONE
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
