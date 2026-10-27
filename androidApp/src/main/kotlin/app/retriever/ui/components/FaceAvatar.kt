package app.retriever.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.scale
import androidx.core.net.toUri
import app.retriever.core.FaceCrop
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.core.android.ImageBytes
import kotlin.math.max
import kotlin.math.roundToInt

/** A person's face in a circle, cut from their cover photo; their initial, or an empty circle, until it loads. */
@Composable
fun FaceAvatar(cover: FaceCrop?, name: String?, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val face by produceState<ImageBitmap?>(null, cover, px) { value = cover?.let { faceCrop(context, it, Thumbnails.bucket(px)) } }
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(size).clip(CircleShape).background(colors.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val picture = face
        if (picture != null) {
            Image(picture, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (name != null) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = colors.primary)
        }
    }
}

/** Cached like any thumbnail, under its photo's address and the face's box. */
private suspend fun faceCrop(context: Context, crop: FaceCrop, px: Int): ImageBitmap? {
    val b = crop.box
    val photo = SourceFile(crop.uri, "", "", "", Kind.PHOTO, 0, 0)
    return Thumbnails.load(context, photo, px, "face:${b.left},${b.top},${b.right},${b.bottom}", Thumbnails.thumbnailDispatcher) { cut(context, crop, px) }
}

/** The face with a margin around it, square and [px] wide; the photo is decoded only as large as that needs. */
private fun cut(context: Context, crop: FaceCrop, px: Int): Bitmap? {
    val b = crop.box
    val faceShare = max(b.right - b.left, b.bottom - b.top).coerceAtLeast(0.05f)
    val longSide = (px * MARGIN / faceShare).roundToInt().coerceIn(px, MAX_DECODE)
    val photo = ImageBytes.bitmap(context, crop.uri.toUri(), longSide) ?: return null
    val side = (max((b.right - b.left) * photo.width, (b.bottom - b.top) * photo.height) * MARGIN).roundToInt()
        .coerceIn(1, minOf(photo.width, photo.height))
    val cx = (b.left + b.right) / 2 * photo.width
    val cy = (b.top + b.bottom) / 2 * photo.height
    val left = (cx - side / 2f).roundToInt().coerceIn(0, photo.width - side)
    val top = (cy - side / 2f).roundToInt().coerceIn(0, photo.height - side)
    val square = Bitmap.createBitmap(photo, left, top, side, side)
    val out = if (side == px) square else square.scale(px, px)
    if (square !== out && square !== photo) square.recycle()
    if (photo !== square) photo.recycle()
    return out
}

/** The crop is this much wider than the face's box, so hair and chin show. */
private const val MARGIN = 1.6f

private const val MAX_DECODE = 2048
