package com.example.autop.core.common

import kotlin.random.Random

/**
 * 时间有序 id 生成器（docs/tech/02 §1.2，UUIDv7 风格）。
 *
 * 布局：48 位毫秒时间戳 + 4 位版本(0111) + 12 位随机 + 2 位变体(10) + 62 位随机，
 * 输出 8-4-4-4-12 十六进制。时间取自注入的 [Clock]，测试可注入 FakeClock 验证有序性。
 */
public class UuidV7IdGenerator(
    private val clock: Clock,
    private val random: Random = Random.Default,
) : IdGenerator {
    override fun newId(): String {
        val ts = clock.nowMillis() and TIMESTAMP_MASK
        val randA = random.nextInt(RAND_A_BOUND)
        val randB = random.nextLong() and RAND_B_MASK
        val msb = (ts shl TIMESTAMP_SHIFT) or (VERSION_BITS shl VERSION_SHIFT).toLong() or randA.toLong()
        val lsb = (VARIANT_BITS shl VARIANT_SHIFT) or randB
        return format(msb, lsb)
    }

    private fun format(
        msb: Long,
        lsb: Long,
    ): String {
        val hex = "%016x%016x".format(msb, lsb)
        return buildString(HEX_LEN_WITH_DASHES) {
            append(hex, 0, GROUP_1_END).append('-')
            append(hex, GROUP_1_END, GROUP_2_END).append('-')
            append(hex, GROUP_2_END, GROUP_3_END).append('-')
            append(hex, GROUP_3_END, GROUP_4_END).append('-')
            append(hex, GROUP_4_END, HEX_LEN)
        }
    }

    private companion object {
        const val TIMESTAMP_MASK = 0xFFFFFFFFFFFFL
        const val VERSION_BITS = 0x7
        const val VARIANT_BITS = 0x2L
        const val RAND_A_BOUND = 0x1000
        const val RAND_B_MASK = 0x3FFFFFFFFFFFFFFFL
        const val HEX_LEN_WITH_DASHES = 36
        const val TIMESTAMP_SHIFT = 16
        const val VERSION_SHIFT = 12
        const val VARIANT_SHIFT = 62
        const val HEX_LEN = 32
        const val GROUP_1_END = 8
        const val GROUP_2_END = 12
        const val GROUP_3_END = 16
        const val GROUP_4_END = 20
    }
}
