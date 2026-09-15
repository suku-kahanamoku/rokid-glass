package cz.suku.rokidglass.transcription

import kotlin.math.abs

/**
 * Software AGC for 16 kHz mono PCM16 audio streamed from the glasses microphone.
 *
 * Removes DC offset/rumble and adaptively boosts quiet audio (e.g. a conversation partner
 * further from the mic) toward a target level, so a distant or quiet voice reaches the
 * recognizer at a usable level instead of being lost in the noise floor.
 */
internal class PcmAudioEnhancer {
    private var dcOffset = 0f
    private var envelope = 0f
    private var gain = MAX_GAIN

    /** Amplifies PCM16 little-endian samples in [data] between [offset] and [offset] + [length], in place. */
    fun process(data: ByteArray, offset: Int, length: Int) {
        val end = offset + length - 1
        var index = offset
        while (index < end) {
            val raw = ((data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)).toShort()
            var sample = raw.toFloat()

            dcOffset += (sample - dcOffset) * DC_ALPHA
            sample -= dcOffset

            val magnitude = abs(sample)
            envelope += (magnitude - envelope) * if (magnitude > envelope) ATTACK else RELEASE

            // Keep the current gain steady during near-silence instead of chasing the noise floor.
            val targetGain = if (envelope > NOISE_FLOOR) {
                (TARGET_LEVEL / envelope).coerceIn(MIN_GAIN, MAX_GAIN)
            } else {
                gain
            }
            gain += (targetGain - gain) * GAIN_SMOOTHING

            val amplified = (sample * gain).coerceIn(
                Short.MIN_VALUE.toFloat(),
                Short.MAX_VALUE.toFloat(),
            ).toInt()
            data[index] = (amplified and 0xff).toByte()
            data[index + 1] = ((amplified shr 8) and 0xff).toByte()
            index += 2
        }
    }

    private companion object {
        const val DC_ALPHA = 0.002f
        const val ATTACK = 0.35f
        const val RELEASE = 0.01f
        const val GAIN_SMOOTHING = 0.08f
        const val NOISE_FLOOR = 40f
        const val TARGET_LEVEL = 7000f
        const val MIN_GAIN = 1f
        const val MAX_GAIN = 14f
    }
}
