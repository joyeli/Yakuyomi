package eu.kanade.tachiyomi.data.nightread

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import li.joye.yakuyomi.engine.Detection
import li.joye.yakuyomi.engine.Detector
import java.nio.FloatBuffer

/**
 * DBNet 的 ONNX Runtime 版偵測器，給 int8 量化模型用。
 *
 * 產品路徑跑的是 NCNN fp16（145.9 MB）。int8 靜態量化（QDQ、per-channel）把模型壓到 73.4 MB
 * 且桌面快 1.78 倍，但那是 ONNX 格式，所以要另一條推論路徑才跑得起來。
 *
 * **前後處理一律借用 [Detector] 的 companion**，換的只有中間那一次前向——否則前處理差一點，
 * 後面每個門檻都會歪，A/B 就不是在比量化而是在比實作差異。
 */
class DbnetOrt(modelPath: String, private val intraOpThreads: Int = 4) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelPath,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(intraOpThreads) },
    )

    fun detect(page: Bitmap): Detection {
        val input = Detector.preprocess(page)
        val name = session.inputNames.first()
        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input.chw),
            longArrayOf(1, 3, input.h.toLong(), input.w.toLong()),
        ).use { tensor ->
            session.run(mapOf(name to tensor)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val dbOut = (res[0].value as Array<Array<Array<FloatArray>>>)[0]    // [2, h, w]
                @Suppress("UNCHECKED_CAST")
                val maskOut = (res[1].value as Array<Array<Array<FloatArray>>>)[0]  // [1, mh, mw]

                val area = input.w * input.h
                val db = FloatArray(2 * area)
                for (c in 0 until 2) {
                    for (y in 0 until input.h) {
                        System.arraycopy(dbOut[c][y], 0, db, c * area + y * input.w, input.w)
                    }
                }
                val mh = maskOut[0].size
                val mw = maskOut[0][0].size
                val mask = FloatArray(mw * mh)
                for (y in 0 until mh) System.arraycopy(maskOut[0][y], 0, mask, y * mw, mw)

                return Detector.postprocess(db, mask, mw, mh, input, page.width, page.height)
            }
        }
    }

    override fun close() = session.close()
}
