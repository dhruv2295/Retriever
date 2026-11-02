package app.retriever.core.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import app.retriever.core.FACE_DIMS
import app.retriever.core.FaceAlign
import app.retriever.core.FaceBox
import app.retriever.core.FaceReader
import app.retriever.core.FoundFace
import app.retriever.core.mlKitExecutor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.hypot

/**
 * People on Android: ML Kit finds each face and its eyes, nose and mouth corners; the face is turned and scaled onto
 * SFace's template ([FaceAlign]) and SFace (assets/sface.tflite, float16 weights) gives its fingerprint. Both load on
 * the first photo, so nothing loads while People is off.
 */
class AndroidFaceReader(private val context: Context) : FaceReader {
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(MIN_FACE)
                .setExecutor(mlKitExecutor)
                .build(),
        )
    }

    private val sface by lazy {
        // Stored uncompressed (AGP leaves .tflite alone), so the model is mapped, not read into the heap.
        val model = context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        }
        Interpreter(model, Interpreter.Options().setNumThreads(2))
    }

    override suspend fun faces(uri: String): List<FoundFace>? {
        val photo = ImageBytes.bitmap(context, uri.toUri(), MAX_SIDE) ?: return null
        val found = detector.process(InputImage.fromBitmap(photo, 0)).awaitResult()
        // Recycled only once ML Kit returns: after a cancellation its native task may still hold the pixels.
        return found.mapNotNull { face ->
            val points = points(face) ?: return@mapNotNull null
            fingerprint(photo, points)?.let { FoundFace(box(face, photo), it) }
        }.also { photo.recycle() }
    }

    /**
     * The five points in [FaceAlign.POINTS]'s order. Slots go by place in the image, not ML Kit's left and right (the
     * person's own). ML Kit has the nose's base where SFace's template has its tip; the fit over five points absorbs it.
     * Null for a face turned too far, or too small to know again.
     */
    private fun points(face: Face): FloatArray? {
        if (abs(face.headEulerAngleY) > MAX_TURN) return null
        fun at(type: Int): PointF? = face.getLandmark(type)?.position
        val eyes = listOf(at(FaceLandmark.LEFT_EYE) ?: return null, at(FaceLandmark.RIGHT_EYE) ?: return null).sortedBy { it.x }
        val mouth = listOf(at(FaceLandmark.MOUTH_LEFT) ?: return null, at(FaceLandmark.MOUTH_RIGHT) ?: return null).sortedBy { it.x }
        val nose = at(FaceLandmark.NOSE_BASE) ?: return null
        if (hypot(eyes[1].x - eyes[0].x, eyes[1].y - eyes[0].y) < MIN_EYES_PX) return null
        return listOf(eyes[0], eyes[1], nose, mouth[0], mouth[1]).flatMap { listOf(it.x, it.y) }.toFloatArray()
    }

    private fun fingerprint(photo: Bitmap, points: FloatArray): FloatArray? {
        val t = FaceAlign.transform(points) ?: return null
        val size = FaceAlign.SIZE
        val crop = createBitmap(size, size)
        val warp = Matrix().apply { setValues(floatArrayOf(t[0], -t[1], t[2], t[1], t[0], t[3], 0f, 0f, 1f)) }
        Canvas(crop).drawBitmap(photo, warp, Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(size * size)
        crop.getPixels(pixels, 0, size, 0, 0, size, size)
        crop.recycle()
        // SFace reads RGB as 0..255 floats, row by row (NHWC); the model scales them itself.
        val input = ByteBuffer.allocateDirect(pixels.size * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF).toFloat())
            input.putFloat(((p shr 8) and 0xFF).toFloat())
            input.putFloat((p and 0xFF).toFloat())
        }
        input.rewind()
        val out = arrayOf(FloatArray(FACE_DIMS))
        // One interpreter, which is not thread-safe; the faces pass reads one photo at a time anyway.
        synchronized(sface) { sface.run(input, out) }
        return out[0]
    }

    private fun box(face: Face, photo: Bitmap): FaceBox {
        val r = face.boundingBox
        val w = photo.width.toFloat()
        val h = photo.height.toFloat()
        return FaceBox((r.left / w).coerceIn(0f, 1f), (r.top / h).coerceIn(0f, 1f), (r.right / w).coerceIn(0f, 1f), (r.bottom / h).coerceIn(0f, 1f))
    }

    private companion object {
        const val MODEL = "sface.tflite"

        /** The photo's longer side as decoded: a face 5% of it wide is still ~60 px. */
        const val MAX_SIDE = 1280

        /** Head width as a fraction of the photo's, ML Kit's smallest. */
        const val MIN_FACE = 0.05f

        /** Eyes closer than this in the decoded photo give SFace too little to go on. */
        const val MIN_EYES_PX = 20f

        /** Degrees: SFace knows faces seen from the front, not in profile. */
        const val MAX_TURN = 45f
    }
}
