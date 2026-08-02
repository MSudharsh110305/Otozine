package net.otozine.player.analysis

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decode a track to mono float PCM using the platform codecs.
 *
 * MediaCodec is the only decoder available on Android, so this is the entry
 * point for every on-device measurement. It handles whatever the phone can
 * play, which in practice covers everything that ends up in this library.
 *
 * Output is 48 kHz mono because that is what the R128 weighting coefficients
 * are defined at. Resampling afterwards would mean either recomputing those
 * coefficients or accepting a measurement that disagrees with the PC's.
 */
object AudioDecoder {

    private const val TAG = "OtoZineDecode"
    private const val TIMEOUT_US = 10_000L

    /**
     * @param maxSeconds cap on how much audio to decode. Analysis does not need
     *   a whole track, and a cap keeps a long file from stalling a batch.
     * @param startSeconds where to begin, used to analyse from the hook rather
     *   than an intro that may be nothing like the rest of the song.
     */
    /**
     * Decode straight into a consumer, one chunk of mono 48 kHz audio at a time.
     *
     * The whole-track variant of this used to hold the decoded PCM in memory,
     * which is fine for a test tone and fatal for real music: four minutes of
     * stereo is roughly 100 MB of floats, and the phone threw OutOfMemoryError
     * on the first full-length song it met. Handing chunks to the caller lets
     * the loudness meter accumulate as it goes and lets the character pass keep
     * only the window it needs.
     *
     * @param onAudio receives a buffer and how much of it is valid. The buffer
     *   is reused between calls, so a consumer that wants to keep the samples
     *   must copy them.
     * @return false if the file could not be decoded at all.
     */
    fun decodeStreaming(
        context: Context,
        uri: Uri,
        maxSeconds: Float = 420f,
        onAudio: (FloatArray, Int) -> Unit,
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                Log.w(TAG, "no audio track in $uri")
                return false
            }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val resampler = Resampler(sourceRate, LoudnessMeter.SAMPLE_RATE)
            var shortBuffer = ShortArray(0)
            var monoBuffer = FloatArray(0)
            var emitted = 0L
            val limit = (LoudnessMeter.SAMPLE_RATE * maxSeconds).toLong()
            var produced = false

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            // Feed the codec everything it will take, then drain everything it
            // has, and only wait when neither side can make progress.
            //
            // The obvious version -- one input and one output per iteration,
            // each with a 10 ms timeout -- decoded at about 10x realtime, which
            // sounds fine until you notice hardware decode should be a hundred
            // times faster than that. Almost all of the time was spent blocked
            // in dequeue calls rather than decoding, because a codec that is
            // ready to accept ten buffers still only got handed one.
            while (!sawOutputEos && emitted < limit) {
                var progressed = false

                while (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                        progressed = true
                    }
                }

                // Wait only if the input side had nothing to do; otherwise poll.
                var outIndex = codec.dequeueOutputBuffer(info, if (progressed) 0L else TIMEOUT_US)
                while (outIndex >= 0) {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val count = shorts.remaining()

                        // Bulk copy: reading a ShortBuffer one index at a time
                        // was the single largest cost in decoding, well above
                        // the arithmetic it was feeding.
                        if (shortBuffer.size < count) shortBuffer = ShortArray(count)
                        shorts.get(shortBuffer, 0, count)

                        val frames = if (channels > 1) count / channels else count
                        if (monoBuffer.size < frames) monoBuffer = FloatArray(frames)
                        if (channels > 1) {
                            for (f in 0 until frames) {
                                var sum = 0f
                                val base = f * channels
                                for (c in 0 until channels) sum += shortBuffer[base + c] / 32768f
                                monoBuffer[f] = sum / channels
                            }
                        } else {
                            for (f in 0 until frames) monoBuffer[f] = shortBuffer[f] / 32768f
                        }

                        val (out, outCount) = resampler.process(monoBuffer, frames)
                        if (outCount > 0) {
                            onAudio(out, outCount)
                            emitted += outCount
                            produced = true
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                        break
                    }
                    if (emitted >= limit) break
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                }
            }
            return produced
        } catch (e: Exception) {
            Log.w(TAG, "decode failed for $uri: ${e.message}")
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Rate conversion that survives being fed in pieces.
     *
     * A chunk boundary rarely lands on a whole output sample, so the fractional
     * read position and the last input sample carry over; resetting them per
     * chunk would put a small discontinuity at every buffer edge.
     */
    private class Resampler(private val from: Int, private val to: Int) {
        private val ratio = from.toDouble() / to
        private var position = 0.0
        private var previous = 0f
        private var primed = false
        private var out = FloatArray(0)

        fun process(input: FloatArray, length: Int): Pair<FloatArray, Int> {
            if (from == to) return input to length
            if (length <= 0) return out to 0

            val estimate = (length / ratio).toInt() + 2
            if (out.size < estimate) out = FloatArray(estimate)

            var written = 0
            while (true) {
                val index = position.toInt()
                if (index >= length) break
                val frac = (position - index).toFloat()
                val a = if (index == 0 && primed) previous else input[index]
                val b = input[minOf(index + 1, length - 1)]
                if (written < out.size) out[written++] = a + (b - a) * frac
                position += ratio
            }
            position -= length
            if (position < 0) position = 0.0
            previous = input[length - 1]
            primed = true
            return out to written
        }
    }

    fun decodeMono48k(
        context: Context,
        uri: Uri,
        maxSeconds: Float = 90f,
        startSeconds: Float = 0f,
    ): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                Log.w(TAG, "no audio track in $uri")
                return null
            }

            extractor.selectTrack(trackIndex)
            if (startSeconds > 0f) {
                extractor.seekTo((startSeconds * 1_000_000).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Decoding into a plain list of chunks and joining once at the end
            // beats growing a single array: a four-minute track is ~10M samples
            // and repeated copying dominates the runtime.
            val chunks = ArrayList<FloatArray>()
            var totalSamples = 0
            val wanted = (sourceRate * maxSeconds).toInt() * channels

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos && totalSamples < wanted) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val count = shorts.remaining()
                        val chunk = FloatArray(count)
                        for (i in 0 until count) chunk[i] = shorts.get(i) / 32768f
                        chunks += chunk
                        totalSamples += count
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }

            if (chunks.isEmpty()) return null
            val interleaved = FloatArray(totalSamples)
            var at = 0
            for (chunk in chunks) {
                chunk.copyInto(interleaved, at)
                at += chunk.size
            }

            val mono = downmix(interleaved, channels)
            return resample(mono, sourceRate, LoudnessMeter.SAMPLE_RATE)
        } catch (e: Exception) {
            Log.w(TAG, "decode failed for $uri: ${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun downmix(interleaved: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            mono[f] = sum / channels
        }
        return mono
    }

    /**
     * Linear-interpolating resample.
     *
     * Not a windowed-sinc: the aliasing it leaves is well above where any of the
     * measurements look, and a proper resampler would cost far more time than
     * the accuracy is worth here.
     */
    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val ratio = from.toDouble() / to
        val outSize = (input.size / ratio).toInt()
        if (outSize <= 1) return input

        val out = FloatArray(outSize)
        for (i in 0 until outSize) {
            val pos = i * ratio
            val index = pos.toInt()
            val frac = (pos - index).toFloat()
            val a = input[index.coerceIn(0, input.size - 1)]
            val b = input[(index + 1).coerceIn(0, input.size - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }
}
