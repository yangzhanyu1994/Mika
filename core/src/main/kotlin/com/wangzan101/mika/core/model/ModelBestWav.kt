package com.wangzan101.mika.core.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * A streamed WAV can have unresolved RIFF and data sizes because the header is sent before
 * synthesis finishes. Rebuild the completed response as a bounded PCM16 WAV for playback.
 */
internal fun playableModelBestWav(bytes: ByteArray): TtsResponse {
    if (sniffAudioMime(bytes) != "audio/wav") throw TtsError.BadAudio("ModelBest returned no WAV header")
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var format: WavFormat? = null
    var offset = 12
    while (offset <= bytes.size - 8) {
        val size = input.getInt(offset + 4).toLong() and 0xffff_ffffL
        val start = offset + 8
        val available = bytes.size - start
        when (String(bytes, offset, 4, Charsets.US_ASCII)) {
            "data" -> {
                val encoding = format ?: throw TtsError.BadAudio("ModelBest WAV has no format chunk")
                // Open-ended data is the final chunk of the streamed WAV.
                val length = when (size) {
                    0L, 0x7fff_ffffL, 0xffff_ffffL -> available
                    else -> if (size <= available) size.toInt() else throw TtsError.BadAudio("ModelBest WAV audio is truncated")
                }
                if (length == 0 || length % encoding.frameSize != 0) {
                    throw TtsError.BadAudio("ModelBest WAV has incomplete audio frames")
                }
                val pcm = encoding.toPcm16(input, start, length)
                val wav = wrapPcm16Wav(pcm, encoding.rate, encoding.channels)
                checkTtsSize(wav)
                return TtsResponse(wav, "audio/wav", encoding.rate)
            }
            "fmt " -> {
                if (size !in 16L..available.toLong()) throw TtsError.BadAudio("ModelBest WAV has an invalid format chunk")
                format = WavFormat.read(input, start, size.toInt())
            }
        }
        val next = start.toLong() + size + (size and 1L)
        if (next > bytes.size) throw TtsError.BadAudio("ModelBest WAV has a truncated chunk")
        offset = next.toInt()
    }
    throw TtsError.BadAudio("ModelBest WAV contains no audio")
}

private data class WavFormat(val encoding: Int, val channels: Int, val rate: Int, val bits: Int) {
    private val sampleSize = bits / 8
    val frameSize = sampleSize * channels

    fun toPcm16(input: ByteBuffer, start: Int, length: Int): ByteArray {
        if (encoding == 1 && bits == 16) return input.array().copyOfRange(start, start + length)
        val decodedSize = length / sampleSize * 2
        if (decodedSize > MAX_TTS_BYTES - 44) throw TtsError.TooLarge(decodedSize.toLong() + 44)
        val output = ByteBuffer.allocate(decodedSize).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in start until start + length step sampleSize) {
            val sample = when {
                encoding == 3 -> {
                    val value = input.getFloat(offset).takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
                    (value * if (value < 0) 32768 else 32767).roundToInt()
                }
                bits == 8 -> ((input.get(offset).toInt() and 0xff) - 128) shl 8
                else -> input.getShort(offset + sampleSize - 2).toInt()
            }
            output.putShort(sample.toShort())
        }
        return output.array()
    }

    companion object {
        // The standard PCM/IEEE-float sub-format GUID has this fixed 12-byte tail.
        private val subFormatTail = byteArrayOf(0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xaa.toByte(), 0, 0x38, 0x9b.toByte(), 0x71)

        fun read(input: ByteBuffer, offset: Int, size: Int): WavFormat {
            val tag = input.getShort(offset).toInt() and 0xffff
            val channels = input.getShort(offset + 2).toInt() and 0xffff
            val rate = input.getInt(offset + 4)
            val blockAlign = input.getShort(offset + 12).toInt() and 0xffff
            val bits = input.getShort(offset + 14).toInt() and 0xffff
            val encoding = if (tag == 0xfffe && size >= 40 &&
                subFormatTail.indices.all { input.get(offset + 28 + it) == subFormatTail[it] }
            ) input.getInt(offset + 24) else tag
            val supported = (encoding == 1 && bits in setOf(8, 16, 24, 32)) || (encoding == 3 && bits == 32)
            if (!supported || channels !in 1..2 || rate !in 8_000..192_000 || blockAlign != channels * (bits / 8)) {
                throw TtsError.BadAudio("ModelBest WAV uses an unsupported sample format")
            }
            return WavFormat(encoding, channels, rate, bits)
        }
    }
}
