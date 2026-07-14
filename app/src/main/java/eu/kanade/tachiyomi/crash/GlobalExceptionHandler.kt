package eu.kanade.tachiyomi.crash

import android.content.Context
import android.content.Intent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class GlobalExceptionHandler private constructor(
    private val applicationContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler,
    private val activityToBeLaunched: Class<*>,
) : Thread.UncaughtExceptionHandler {

    object ThrowableSerializer : KSerializer<Throwable> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Throwable", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): Throwable =
            Throwable(message = decoder.decodeString())

        override fun serialize(encoder: Encoder, value: Throwable) =
            encoder.encodeString(value.stackTraceToString())
    }

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        logcat(priority = LogPriority.ERROR, throwable = exception)
        launchActivity(applicationContext, activityToBeLaunched, exception)
        defaultHandler.uncaughtException(thread, exception)
    }

    private fun launchActivity(
        applicationContext: Context,
        activity: Class<*>,
        exception: Throwable,
    ) {
        val intent = Intent(applicationContext, activity).apply {
            putExtra(INTENT_EXTRA, Json.encodeToString(ThrowableSerializer, exception))
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        applicationContext.startActivity(intent)
    }

    companion object {
        private const val INTENT_EXTRA = "Throwable"

        fun initialize(
            applicationContext: Context,
            activityToBeLaunched: Class<*>,
        ) {
            val handler = GlobalExceptionHandler(
                applicationContext,
                Thread.getDefaultUncaughtExceptionHandler() as Thread.UncaughtExceptionHandler,
                activityToBeLaunched,
            )
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }

        fun getThrowableFromIntent(intent: Intent): Throwable? {
            return try {
                Json.decodeFromString(ThrowableSerializer, intent.getStringExtra(INTENT_EXTRA)!!)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Wasn't able to retrieve throwable from intent" }
                null
            }
        }

        /**
         * 用同一套崩潰畫面（含分享鈕）顯示任意 [throwable]，不需真的觸發未捕捉例外。
         * 給 [NativeCrashReporter] 開機還原上次**原生** crash 用——原生 crash 繞過本 handler、不會有畫面，
         * 靠這條把 ApplicationExitInfo 的 tombstone 塞進同一個畫面讓使用者分享。
         */
        fun showCrashScreen(context: Context, activity: Class<*>, throwable: Throwable) {
            val intent = Intent(context, activity).apply {
                putExtra(INTENT_EXTRA, Json.encodeToString(ThrowableSerializer, throwable))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
        }
    }
}
