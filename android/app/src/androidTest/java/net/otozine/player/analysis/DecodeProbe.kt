package net.otozine.player.analysis

import android.content.ContentUris
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.nio.ByteOrder

/**
 * Localises the cost of decoding.
 *
 * Restructuring the codec loop only moved 20s/track to 19.8s, which says the
 * time is not going where it looked like it was going. Rather than guess again,
 * this splits the pipeline into stages and times each one separately:
 *
 *   1. read     -- pull compressed packets, never decode them
 *   2. decode   -- decode and immediately discard the PCM
 *   3. convert  -- decode, downmix, resample (the real path)
 *
 * The differences say whether the bottleneck is I/O through the content
 * provider, the codec itself, or the arithmetic layered on top.
 */
class DecodeProbe {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun whereDoesTheTimeGo() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "android.permission.READ_MEDIA_AUDIO")

        for ((name, uri) in findTracks().take(3)) {
            Log.i(TAG, "--- $name ---")
            Log.i(TAG, "  read only      ${time { readOnly(uri) }} ms")
            Log.i(TAG, "  decode+discard ${time { decodeDiscard(uri) }} ms")
            Log.i(TAG, "  full path      ${time { fullPath(uri) }} ms")
        }
    }

    private inline fun time(block: () -> Unit): Long {
        val t = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - t
    }

    /** Pull every compressed packet; no decoding at all. */
    private fun readOnly(uri: Uri) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val i = audioTrack(ex) ?: return
            ex.selectTrack(i)
            val buf = java.nio.ByteBuffer.allocate(1 shl 16)
            var packets = 0
            while (ex.readSampleData(buf, 0) >= 0) { packets++; ex.advance() }
            Log.i(TAG, "    ($packets packets)")
        } finally { ex.release() }
    }

    /** Decode, then throw the PCM away without touching it. */
    private fun decodeDiscard(uri: Uri) = runCodec(uri, convert = false)

    /** Decode and run the real downmix + resample. */
    private fun fullPath(uri: Uri) = runCodec(uri, convert = true)

    private fun runCodec(uri: Uri, convert: Boolean) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val index = audioTrack(ex) ?: return
            ex.selectTrack(index)
            val format = ex.getTrackFormat(index)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var shorts = ShortArray(0)
            var mono = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inEos = false
            var outEos = false

            while (!outEos) {
                var progressed = false
                while (!inEos) {
                    val ii = codec.dequeueInputBuffer(0)
                    if (ii < 0) break
                    val b = codec.getInputBuffer(ii)!!
                    val size = ex.readSampleData(b, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inEos = true
                    } else {
                        codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                        ex.advance()
                        progressed = true
                    }
                }
                var oi = codec.dequeueOutputBuffer(info, if (progressed) 0L else 10_000L)
                while (oi >= 0) {
                    if (convert && info.size > 0) {
                        val b = codec.getOutputBuffer(oi)!!
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val sb = b.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val n = sb.remaining()
                        if (shorts.size < n) shorts = ShortArray(n)
                        sb.get(shorts, 0, n)
                        val frames = if (channels > 1) n / channels else n
                        if (mono.size < frames) mono = FloatArray(frames)
                        for (f in 0 until frames) mono[f] = shorts[f * channels] / 32768f
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outEos = true; break }
                    oi = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; ex.release()
        }
    }

    private fun audioTrack(ex: MediaExtractor): Int? {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            ) return i
        }
        return null
    }

    private fun findTracks(): List<Pair<String, Uri>> {
        val out = mutableListOf<Pair<String, Uri>>()
        val c = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        context.contentResolver.query(
            c,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf("%songs%"),
            MediaStore.Audio.Media.DISPLAY_NAME,
        )?.use {
            while (it.moveToNext()) out += it.getString(1) to ContentUris.withAppendedId(c, it.getLong(0))
        }
        return out
    }

    companion object { private const val TAG = "OtoZineProbe" }
}
