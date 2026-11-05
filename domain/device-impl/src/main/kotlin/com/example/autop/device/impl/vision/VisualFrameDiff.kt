/**
 * 帧差感知（docs/design/visual-screen-parsing §7）：64×64 灰度 aHash＋SSIM 无变化判据。
 * 输入是已降采样的灰度像素（Android 侧解码缩图后注入），本类纯 Kotlin 可单测。
 */
package com.example.autop.device.impl.vision

/** 帧签名（aHash 位图＋降采样灰度）。 */
internal class FrameSignature(
    val hash: Long,
    val gray: IntArray,
)

/** 帧差计算器。 */
internal object VisualFrameDiff {
    /** 降采样边长（64×64 灰度）。 */
    const val GRAY_EDGE: Int = 64

    /** aHash 网格边长（8×8 → 64 bit）。 */
    private const val HASH_GRID: Int = 8
    private const val SSIM_C1: Double = 6.5025
    private const val SSIM_C2: Double = 58.5225

    /** 由 [GRAY_EDGE]×[GRAY_EDGE] 灰度像素计算帧签名。 */
    fun signature(gray: IntArray): FrameSignature {
        val block = GRAY_EDGE / HASH_GRID
        val means = LongArray(HASH_GRID * HASH_GRID)
        var total = 0L
        for (by in 0 until HASH_GRID) {
            for (bx in 0 until HASH_GRID) {
                val sum = blockSum(gray, bx, by, block)
                means[by * HASH_GRID + bx] = sum / (block * block)
                total += sum
            }
        }
        val avg = total / (GRAY_EDGE * GRAY_EDGE)
        var hash = 0L
        means.forEachIndexed { i, m -> if (m >= avg) hash = hash or (1L shl i) }
        return FrameSignature(hash, gray)
    }

    /** (bx, by) 网格块内灰度求和。 */
    private fun blockSum(
        gray: IntArray,
        bx: Int,
        by: Int,
        block: Int,
    ): Long {
        var sum = 0L
        for (y in 0 until block) {
            for (x in 0 until block) {
                sum += gray[(by * block + y) * GRAY_EDGE + bx * block + x]
            }
        }
        return sum
    }

    /** 帧差用 [GRAY_EDGE]×[GRAY_EDGE] 亮度降采样（近邻取样，diff 判据对采样方式不敏感）。 */
    fun grayOf(image: VisionImage): IntArray {
        val gray = IntArray(GRAY_EDGE * GRAY_EDGE)
        for (y in 0 until GRAY_EDGE) {
            val sy = y * image.height / GRAY_EDGE
            for (x in 0 until GRAY_EDGE) {
                val sx = x * image.width / GRAY_EDGE
                gray[y * GRAY_EDGE + x] = image.luma(sx, sy)
            }
        }
        return gray
    }

    /** 无变化判据：Hamming ≤ [maxHamming] 且 SSIM ≥ [minSsim]。 */
    fun unchanged(
        a: FrameSignature,
        b: FrameSignature,
        maxHamming: Int,
        minSsim: Double,
    ): Boolean = hamming(a.hash, b.hash) <= maxHamming && ssim(a.gray, b.gray) >= minSsim

    /** aHash 汉明距离。 */
    fun hamming(
        a: Long,
        b: Long,
    ): Int = (a xor b).countOneBits()

    /** 全局 SSIM（64×64 灰度，阈值判据用的轻量近似）。 */
    fun ssim(
        a: IntArray,
        b: IntArray,
    ): Double {
        val n = a.size
        val muA = a.sum().toDouble() / n
        val muB = b.sum().toDouble() / n
        var varA = 0.0
        var varB = 0.0
        var cov = 0.0
        for (i in 0 until n) {
            val da = a[i] - muA
            val db = b[i] - muB
            varA += da * da
            varB += db * db
            cov += da * db
        }
        varA /= n
        varB /= n
        cov /= n
        return ((2 * muA * muB + SSIM_C1) * (2 * cov + SSIM_C2)) /
            ((muA * muA + muB * muB + SSIM_C1) * (varA + varB + SSIM_C2))
    }
}
