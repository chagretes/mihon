package eu.kanade.tachiyomi.ui.reader.viewer.guided

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.security.MessageDigest

class BubbleDetector(
    private val context: Context,
    private val httpClient: OkHttpClient,
) : AutoCloseable {

    private val mutex = Mutex()
    private val environment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    suspend fun detect(
        bitmap: Bitmap,
        direction: GuidedReadingDirection,
    ): List<GuidedRegion> = mutex.withLock {
        val activeSession = session ?: createSession().also { session = it }
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        try {
            val inputBuffer = bitmapToPlanarRgb(scaledBitmap)
            val originalSize = LongBuffer.wrap(longArrayOf(bitmap.width.toLong(), bitmap.height.toLong()))

            OnnxTensor.createTensor(
                environment,
                inputBuffer,
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
            ).use { imageTensor ->
                OnnxTensor.createTensor(environment, originalSize, longArrayOf(1, 2)).use { sizeTensor ->
                    activeSession.run(
                        mapOf(
                            INPUT_IMAGE_NAME to imageTensor,
                            INPUT_SIZE_NAME to sizeTensor,
                        ),
                    ).use { readRegions(it, bitmap.width, bitmap.height) }
                }
            }
        } finally {
            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
        }
            .let(::removeDuplicates)
            .map { it.copy(bounds = it.bounds.expanded(REGION_PADDING)) }
            .let { GuidedRegionOrderer.order(it, direction) }
    }

    override fun close() {
        session?.close()
        session = null
    }

    private fun createSession(): OrtSession {
        val model = ensureModel()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(INFERENCE_THREADS)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return options.use { environment.createSession(model.absolutePath, it) }
    }

    private fun ensureModel(): File {
        val modelDirectory = File(context.filesDir, MODEL_DIRECTORY).apply { mkdirs() }
        val model = File(modelDirectory, MODEL_FILENAME)
        if (model.isFile && model.sha256() == MODEL_SHA256) return model
        if (model.exists() && !model.delete()) error("Unable to replace an invalid guided reading model")

        val temporaryModel = File(modelDirectory, "$MODEL_FILENAME.part")
        if (temporaryModel.exists() && !temporaryModel.delete()) {
            error("Unable to remove an incomplete guided reading model")
        }

        val request = Request.Builder().url(MODEL_URL).build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Unable to download guided reading model: HTTP ${response.code}" }
            val body = checkNotNull(response.body) { "Guided reading model response was empty" }
            body.byteStream().use { input ->
                FileOutputStream(temporaryModel).use { output -> input.copyTo(output) }
            }
        }

        check(temporaryModel.sha256() == MODEL_SHA256) { "Guided reading model checksum does not match" }
        check(temporaryModel.renameTo(model)) { "Unable to install guided reading model" }
        return model
    }

    private fun bitmapToPlanarRgb(bitmap: Bitmap) = ByteBuffer
        .allocateDirect(3 * INPUT_SIZE * INPUT_SIZE * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            pixels.forEach { put(((it shr 16) and 0xFF) / 255f) }
            pixels.forEach { put(((it shr 8) and 0xFF) / 255f) }
            pixels.forEach { put((it and 0xFF) / 255f) }
            rewind()
        }

    @Suppress("UNCHECKED_CAST")
    private fun readRegions(
        result: OrtSession.Result,
        imageWidth: Int,
        imageHeight: Int,
    ): List<GuidedRegion> {
        val labels = (result[OUTPUT_LABELS_NAME].get().value as Array<LongArray>)[0]
        val boxes = (result[OUTPUT_BOXES_NAME].get().value as Array<Array<FloatArray>>)[0]
        val scores = (result[OUTPUT_SCORES_NAME].get().value as Array<FloatArray>)[0]

        return labels.indices.mapNotNull { index ->
            if (labels[index] != BUBBLE_CLASS || scores[index] < CONFIDENCE_THRESHOLD) return@mapNotNull null
            val box = boxes[index]
            val bounds = NormalizedRect(
                left = (box[0] / imageWidth).coerceIn(0f, 1f),
                top = (box[1] / imageHeight).coerceIn(0f, 1f),
                right = (box[2] / imageWidth).coerceIn(0f, 1f),
                bottom = (box[3] / imageHeight).coerceIn(0f, 1f),
            )
            if (bounds.width <= 0f || bounds.height <= 0f) null else GuidedRegion(bounds, scores[index])
        }
    }

    private fun removeDuplicates(regions: List<GuidedRegion>): List<GuidedRegion> =
        regions.sortedByDescending(GuidedRegion::confidence)
            .fold(mutableListOf()) { accepted, candidate ->
                if (accepted.none { it.bounds.intersectionOverUnion(candidate.bounds) >= DUPLICATE_IOU }) {
                    accepted += candidate
                }
                accepted
            }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val INPUT_SIZE = 640
        const val INFERENCE_THREADS = 2
        const val CONFIDENCE_THRESHOLD = 0.45f
        const val DUPLICATE_IOU = 0.7f
        const val REGION_PADDING = 0.12f
        const val BUBBLE_CLASS = 0L

        const val INPUT_IMAGE_NAME = "images"
        const val INPUT_SIZE_NAME = "orig_target_sizes"
        const val OUTPUT_LABELS_NAME = "labels"
        const val OUTPUT_BOXES_NAME = "boxes"
        const val OUTPUT_SCORES_NAME = "scores"

        const val MODEL_DIRECTORY = "guided_reading"
        const val MODEL_FILENAME = "bubble-detector-v4-s-int8.onnx"
        const val MODEL_SHA256 = "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79"
        const val MODEL_URL =
            "https://huggingface.co/NorwayFish/manga-bubble-detector/resolve/" +
                "bcf342f296beb56005e044da15990502629015de/detector-v4-s_int8.onnx"
    }
}
