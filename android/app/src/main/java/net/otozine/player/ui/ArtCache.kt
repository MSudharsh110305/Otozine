package net.otozine.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tiny bitmap cache for cover art.
 *
 * Deliberately not an image library: art comes either from a file the Librarian
 * sized, or from the picture embedded in the audio file itself. There is no
 * network fetching and no placeholder chain -- a missing cover is drawn as a
 * generated tile. Pulling in Coil for that would be more moving parts than the
 * job has.
 *
 * Budget is an eighth of the heap, which on this device is plenty for a screen
 * of thumbnails plus the full-size now-playing image.
 */
object ArtCache {

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun get(context: Context, path: String, targetPx: Int): Bitmap? {
        val key = "$path@$targetPx"
        cache.get(key)?.let { return it }

        val bytes = if (path.startsWith("content://")) {
            embeddedPicture(context, path.toUri()) ?: return null
        } else {
            val file = File(path)
            if (!file.isFile) return null
            file.readBytes()
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null

        // Downsample at decode time; loading a 600px cover for a 48dp thumbnail
        // wastes both memory and the decode itself.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2

        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565   // covers have no alpha
            },
        ) ?: return null

        cache.put(key, decoded)
        return decoded
    }

    /**
     * Cover art stored inside the audio file itself.
     *
     * Tracks played from the phone's own folders have no artwork file beside
     * them, so the app showed a generated tile for them while the lock-screen
     * notification showed a real cover -- Media3 reads the embedded picture, and
     * the app did not. Same song, two different covers, which reads as a bug
     * whatever the explanation.
     */
    private fun embeddedPicture(context: Context, uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun clear() = cache.evictAll()
}

/** Load art off the main thread; returns null until ready, or if absent. */
@Composable
fun rememberArt(path: String?, targetPx: Int): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(path, targetPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path, targetPx) {
        bitmap = if (path == null) null
        else withContext(Dispatchers.IO) { ArtCache.get(context, path, targetPx) }
    }
    return bitmap
}
