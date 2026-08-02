package net.otozine.player.library

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.nio.ByteOrder

/**
 * Build the Opus phone tier on the phone itself.
 *
 * The exporter used to say this needed ffmpeg and therefore a PC. That was true
 * of the original design and is not true any more: Android has shipped an Opus
 * encoder and OGG muxing since API 29, and this app already decodes and
 * analyses audio on device. The remaining reason to reach for a PC when adding
 * music was this one step.
 *
 * Stereo is preserved deliberately. The analysis path downmixes to mono because
 * loudness and tempo do not care about the stereo image, but this output is what
 * gets *listened to* -- folding a library to mono to save a few megabytes would
 * be a permanent loss to fix a problem nobody has on a 32 GB drive.
 */
object OpusTranscoder {

    private const val TAG = "OtoZineTranscode"
    private const val TIMEOUT_US = 10_000L
    private const val RATE = 48_000              // Opus encodes at 48 kHz
    const val BITRATE = 128_000

    /**
     * Whether this device can encode Opus at all.
     *
     * Checked rather than assumed: the encoder is a software component that a
     * vendor could have stripped, and the honest fallback is to copy the
     * original to the drive's inbox and let a PC do the split later.
     */
    fun isAvailable(): Boolean = encoderName() != null

    private fun encoderName(): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, true) }) {
                return info.name
            }
        }
        return null
    }

    /**
     * Transcode [source] into [destination] as an Opus stream in an OGG container.
     *
     * @return true if a complete file was written.
     */
    fun transcode(context: Context, source: Uri, destination: ParcelFileDescriptor): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            extractor.setDataSource(context, source, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it)
                    .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false

            extractor.selectTrack(trackIndex)
            val inFormat = extractor.getTrackFormat(trackIndex)
            val sourceRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)

            decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(inFormat, null, null, 0)
            decoder.start()

            val outFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, channels,
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(
                    MediaFormat.KEY_PCM_ENCODING,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                )
            }
            encoder = MediaCodec.createByCodecName(encoderName() ?: return false)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(destination.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)

            val resampler = Resampler(sourceRate, RATE, channels)
            val decodeInfo = MediaCodec.BufferInfo()
            val encodeInfo = MediaCodec.BufferInfo()
            var muxTrack = -1
            var presentationUs = 0L

            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var pending = ShortArray(0)
            var pendingCount = 0

            while (!encoderDone) {
                // 1. compressed input -> decoder
                if (!extractorDone) {
                    val index = decoder.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2. decoder output -> resample -> holding buffer
                if (!decoderDone) {
                    val index = decoder.dequeueOutputBuffer(decodeInfo, 0)
                    if (index >= 0) {
                        if (decodeInfo.size > 0) {
                            val buffer = decoder.getOutputBuffer(index)!!
                            buffer.position(decodeInfo.offset)
                            buffer.limit(decodeInfo.offset + decodeInfo.size)
                            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val count = shorts.remaining()
                            val raw = ShortArray(count)
                            shorts.get(raw, 0, count)

                            val (converted, length) = resampler.process(raw, count)
                            if (pending.size < pendingCount + length) {
                                pending = pending.copyOf((pendingCount + length) * 2)
                            }
                            System.arraycopy(converted, 0, pending, pendingCount, length)
                            pendingCount += length
                        }
                        decoder.releaseOutputBuffer(index, false)
                        if (decodeInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderDone = true
                        }
                    }
                }

                // 3. holding buffer -> encoder
                while (pendingCount > 0 || decoderDone) {
                    val index = encoder.dequeueInputBuffer(0)
                    if (index < 0) break
                    val buffer = encoder.getInputBuffer(index)!!
                    val capacityShorts = buffer.capacity() / 2
                    val take = minOf(capacityShorts, pendingCount)

                    if (take == 0 && decoderDone) {
                        encoder.queueInputBuffer(
                            index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        break
                    }

                    buffer.clear()
                    buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pending, 0, take)
                    encoder.queueInputBuffer(index, 0, take * 2, presentationUs, 0)

                    presentationUs += 1_000_000L * (take / channels) / RATE
                    System.arraycopy(pending, take, pending, 0, pendingCount - take)
                    pendingCount -= take
                }

                // 4. encoder output -> muxer
                val index = encoder.dequeueOutputBuffer(encodeInfo, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        val buffer = encoder.getOutputBuffer(index)!!
                        val isConfig =
                            encodeInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (encodeInfo.size > 0 && muxerStarted && !isConfig) {
                            buffer.position(encodeInfo.offset)
                            buffer.limit(encodeInfo.offset + encodeInfo.size)
                            muxer.writeSampleData(muxTrack, buffer, encodeInfo)
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if (encodeInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                        }
                    }
                }
            }

            return muxerStarted
        } catch (e: Exception) {
            Log.w(TAG, "transcode failed: ${e.message}")
            return false
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Interleaved rate conversion that survives being fed in pieces.
     *
     * Channels are resampled independently but stay interleaved, and the read
     * position carries across chunks -- restarting it per buffer would put a
     * click at every boundary, several hundred times a song.
     */
    private class Resampler(from: Int, to: Int, private val channels: Int) {
        private val ratio = from.toDouble() / to
        private val passthrough = from == to
        private var position = 0.0
        private var out = ShortArray(0)

        fun process(input: ShortArray, length: Int): Pair<ShortArray, Int> {
            if (passthrough || length <= 0) return input to length

            val frames = length / channels
            val estimate = ((frames / ratio).toInt() + 2) * channels
            if (out.size < estimate) out = ShortArray(estimate)

            var written = 0
            while (true) {
                val frame = position.toInt()
                if (frame >= frames) break
                val frac = position - frame
                val next = minOf(frame + 1, frames - 1)
                for (c in 0 until channels) {
                    val a = input[frame * channels + c].toDouble()
                    val b = input[next * channels + c].toDouble()
                    if (written < out.size) {
                        out[written++] = (a + (b - a) * frac).toInt().toShort()
                    }
                }
                position += ratio
            }
            position -= frames
            if (position < 0) position = 0.0
            return out to written
        }
    }
}
