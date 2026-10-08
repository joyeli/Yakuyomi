package eu.kanade.tachiyomi.ui.reader

// 閱讀器裡「線上章（還沒下載）」的兩個流程判斷，純函式（JVM 單元測試見 OnlineChapterFlowTest）：
//  - [OnlineNightPrompt]：線上章要夜讀時，問不問「這話要先下載才能產生夜讀版，要下載嗎？」。
//  - [OnlineDownloadPoll]：按了下載之後，每次輪詢該繼續等、重載這一話，還是放棄（即時翻譯的線上路徑也用這個）。

/**
 * 線上章要夜讀時該怎麼辦（`ReaderViewModel`）。線上章沒有章節夾、夜讀版做不了；以前只提示「需先下載」，現在改成問一次要不要下載，
 * 按「下載」就下載成鬆散資料夾、下載完排入夜讀、重載這一話（保留頁碼），夜讀版做好就照常換上。
 */
object OnlineNightPrompt {

    /** 觸發的來源。 */
    enum class Trigger {
        /** 打開夜讀模式（懸浮鈕或閱讀設定面板）。每話只問一次，問過就不再問。 */
        NIGHT_ON,

        /** 明確要夜讀版：點了灰色的檔位鈕、長按選單「為這一話產生夜讀版」。每次都問。 */
        EXPLICIT,
    }

    enum class Action {
        /** 不是線上章、或夜讀總開關關著：照原本的路走（呼叫端自己處理，例如壓縮檔章仍提示「需先下載」）。 */
        NONE,

        /** 跳確認框「這話要先下載才能產生夜讀版，要下載嗎？」。 */
        ASK,

        /** 已經按過「下載」、還在下載：提示下載完成後會自動產生，不再問。 */
        DOWNLOADING,

        /** 什麼都不做（開夜讀模式時這話已經問過、或已經在下載）。 */
        SKIP,
    }

    /**
     * [online]＝這話目前是線上章（閱讀器用的是網路載入器）；[nightEnabled]＝夜讀總開關；[asked]＝這次閱讀已經問過這話；
     * [requested]＝這話已經按過「下載」（下載中或剛下載完、還沒重載）。
     */
    fun decide(trigger: Trigger, online: Boolean, nightEnabled: Boolean, asked: Boolean, requested: Boolean): Action =
        when {
            !nightEnabled || !online -> Action.NONE
            requested -> if (trigger == Trigger.EXPLICIT) Action.DOWNLOADING else Action.SKIP
            trigger == Trigger.NIGHT_ON && asked -> Action.SKIP
            else -> Action.ASK
        }
}

/** 線上章按了下載之後的輪詢（每 1.5 秒看一次章節夾出現了沒有）。 */
object OnlineDownloadPoll {

    enum class Step {
        /** 還沒下載完：再等。 */
        WAIT,

        /** 下載完、是鬆散資料夾：重載這一話（保留頁碼），進已下載的路徑。 */
        RELOAD,

        /** 下載完、卻是壓縮檔：即時翻譯與夜讀都做不了，維持線上原圖。 */
        ARCHIVE,

        /** 使用者已經換到別話：不重載（下載與排入的工作在背景照做）。 */
        LEFT,

        /** 等太久（下載失敗或卡住）：放棄重載。 */
        TIMEOUT,
    }

    /**
     * 判斷順序：先看使用者還在不在這話，再看章節夾出現了沒有（出現就不管有沒有逾時），最後才看逾時。
     * [dirFound]＝找得到章節夾（下載完成、改名成正式名稱後才有）；[isDirectory]＝它是資料夾（不是 .cbz）。
     */
    fun step(stillCurrent: Boolean, dirFound: Boolean, isDirectory: Boolean, timedOut: Boolean): Step = when {
        !stillCurrent -> Step.LEFT
        dirFound -> if (isDirectory) Step.RELOAD else Step.ARCHIVE
        timedOut -> Step.TIMEOUT
        else -> Step.WAIT
    }
}
