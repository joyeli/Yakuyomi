package eu.kanade.tachiyomi.data.translation

import android.app.ActivityManager.RunningAppProcessInfo

// 開 app 時要不要預暖翻譯引擎的判斷（[StartupEnginePrewarm] 用）：純函式，單元測試直接測（StartupPrewarmPolicyTest）。
//
// 2026-10-06 使用者拍板：只有使用者自己把 app 開起來才預暖；背景工作（WorkManager、廣播、前景服務）把行程叫起來時不預暖。
// 真機紀錄：背景叫起來的行程照樣預暖，吃掉約 15 秒 CPU（偵測 3.5 s、OCR 9 s、去字 1.7 s）和 1.3–2 GB 原生記憶體，
// 而使用者根本沒在看。翻譯／夜讀佇列真的有活要做時仍照舊在要用時才載引擎，這裡管的只是「先載好等著」那一次。

/** 預暖判斷的三種結果；[word] 是寫進診斷紀錄的字。 */
internal enum class PrewarmAction(val word: String) {
    /** 現在就預暖。 */
    START("started"),

    /** 現在不做，等使用者把 app 開到前景、第一個畫面出來再判斷一次。 */
    DEFER("deferred"),

    /** 這個行程不預暖（條件不符，或引擎已經在了）。 */
    SKIP("skipped"),
}

/** 一次判斷：做什麼、為什麼。 */
internal data class PrewarmDecision(val action: PrewarmAction, val reason: String) {
    /** 診斷紀錄那一行的開頭，例如 `prewarm deferred reason=background-start`。 */
    fun logLine(): String = "prewarm ${action.word} reason=$reason"
}

/** 前景那次判斷是被什麼叫起來的；[word] 是預暖開始時記的原因。 */
internal enum class PrewarmTrigger(val word: String) {
    /** 主畫面的第一個分頁內容出來了（MainActivity.ready）。 */
    FIRST_SCREEN("first-screen-ready"),

    /** 回到前景後一直沒等到主畫面的訊號（例如起始頁是「更多」、或直接開進閱讀器），等滿逾時才判斷。 */
    NO_SCREEN_SIGNAL("no-first-screen-signal"),
}

/**
 * 行程剛起來（App.onCreate）時的判斷：**永遠不在這裡預暖**。
 *  - 翻譯總開關或即時翻譯關著 → 這個行程不預暖（[PrewarmAction.SKIP]）；之後使用者打開開關時，開關本身會預暖。
 *  - 否則延後（[PrewarmAction.DEFER]）：使用者點開的（[importance] 是 foreground）等第一個畫面出來；
 *    其他（背景工作、廣播、前景服務、查不到）記成 background-start——等使用者真的把 app 開到前景才會再判斷。
 *
 * [importance]＝[RunningAppProcessInfo.importance]，只用來分辨紀錄裡的原因，不影響做不做：真正的閘門是
 * 「app 到了前景」這個事件（背景行程收不到它）。
 */
internal fun decidePrewarmAtProcessStart(
    masterEnabled: Boolean,
    liveTranslate: Boolean,
    importance: Int,
): PrewarmDecision = when {
    !masterEnabled -> PrewarmDecision(PrewarmAction.SKIP, "translation-off")
    !liveTranslate -> PrewarmDecision(PrewarmAction.SKIP, "live-translate-off")
    importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND ->
        PrewarmDecision(PrewarmAction.DEFER, "wait-first-screen")
    else -> PrewarmDecision(PrewarmAction.DEFER, "background-start")
}

/**
 * app 到了前景、第一個畫面出來後（或等滿逾時）的判斷。
 *  - 判斷當下已經回到背景 → 延後到下次回前景（[PrewarmAction.DEFER] went-background）。
 *  - 總開關、即時翻譯關著 → 不預暖。
 *  - 引擎已經建好或正在建（例如佇列剛好在翻）→ 不重複載。
 *  - 金鑰或模型不齊（[engineReady] false）→ 不預暖，建了也只會失敗。
 *  - 其餘 → 預暖，原因記 [trigger]。
 */
internal fun decidePrewarmInForeground(
    trigger: PrewarmTrigger,
    stillForeground: Boolean,
    masterEnabled: Boolean,
    liveTranslate: Boolean,
    engineReady: Boolean,
    engineWarm: Boolean,
    engineLoading: Boolean,
): PrewarmDecision = when {
    !stillForeground -> PrewarmDecision(PrewarmAction.DEFER, "went-background")
    !masterEnabled -> PrewarmDecision(PrewarmAction.SKIP, "translation-off")
    !liveTranslate -> PrewarmDecision(PrewarmAction.SKIP, "live-translate-off")
    engineWarm -> PrewarmDecision(PrewarmAction.SKIP, "already-warm")
    engineLoading -> PrewarmDecision(PrewarmAction.SKIP, "already-loading")
    !engineReady -> PrewarmDecision(PrewarmAction.SKIP, "engine-not-ready")
    else -> PrewarmDecision(PrewarmAction.START, trigger.word)
}
