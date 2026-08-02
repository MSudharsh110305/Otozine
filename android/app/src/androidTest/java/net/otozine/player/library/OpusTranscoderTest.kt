package net.otozine.player.library

import android.content.ContentUris
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import net.otozine.player.analysis.AudioDecoder
import net.otozine.player.analysis.LoudnessMeter
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Proves the phone can build the Opus tier by itself.
 *
 * The whole "no PC needed" claim rests on this: if the device cannot encode
 * Opus, copying music to the drive can only drop originals in the inbox and the
 * split still waits for a computer. Checked against a real song rather than a
 * tone, and the output is decoded back so a file that is written but unplayable
 * cannot pass.
 */
class OpusTranscoderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun encodesRealMusicToPlayableOpus() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "android.permission.READ_MEDIA_AUDIO")

        Log.i(TAG, "opus encoder available: ${OpusTranscoder.isAvailable()}")
        assertTrue("no Opus encoder on this device", OpusTranscoder.isAvailable())

        val (name, source) = firstTrack() ?: run {
            assertTrue("no audio under Download/songs", false); return
        }

        val out = File(context.cacheDir, "transcode-test.opus")
        if (out.exists()) out.delete()

        val started = System.currentTimeMillis()
        val ok = ParcelFileDescriptor.open(
            out, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE,
        ).use { OpusTranscoder.transcode(context, source, it) }
        val elapsed = System.currentTimeMillis() - started

        assertTrue("transcode reported failure for $name", ok)
        assertTrue("no output written", out.length() > 0)

        // Decoding the result is the real check: a muxer can happily produce a
        // file that no decoder will open.
        var samples = 0L
        val decoded = AudioDecoder.decodeStreaming(context, Uri.fromFile(out)) { _, length ->
            samples += length
        }
        val seconds = samples.toFloat() / LoudnessMeter.SAMPLE_RATE

        Log.i(
            TAG,
            "%s -> %.1f MB in %dms, decodes to %.1fs".format(
                name, out.length() / 1024.0 / 1024.0, elapsed, seconds,
            ),
        )
        assertTrue("output did not decode", decoded)
        assertTrue("output decoded to almost nothing", seconds > 10f)

        out.delete()
    }

    private fun firstTrack(): Pair<String, Uri>? {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%songs%"),
            MediaStore.Audio.Media.DISPLAY_NAME,
        )?.use { c ->
            if (c.moveToFirst()) {
                return c.getString(1) to ContentUris.withAppendedId(collection, c.getLong(0))
            }
        }
        return null
    }

    companion object {
        private const val TAG = "OtoZineTranscodeTest"
    }
}
