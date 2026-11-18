/**
 * 卫星符号几何（设计稿 `.tmp-icons/ball-face-events.html` 的 SAT_* 路径，逐条移植）。
 *
 * 符号画在卫星圆上，局部坐标以卫星圆心为原点、单位与设计稿一致；颜色由绘制方决定
 * （眼白 onPrimary，见 [AutopBallFace]），本文件只描述形状。
 */
@file:Suppress("MagicNumber") // 路径坐标按设计稿原值保留，具名化后无法与设计稿逐条对照。

package com.example.autop.core.ui.component.ballface

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser

/** 卫星符号。[NONE] 只有卫星圆底（空闲）。 */
public enum class BallFaceSymbol {
    NONE,

    /** 等待回答：问号。 */
    QUESTION,

    /** 被拦截：感叹号。 */
    EXCLAIM,

    /** 已暂停：双竖条。 */
    PAUSE,

    /** 完成：心形。 */
    HEART,

    /** 失败：💢 怒气纹（SAT_VEIN_ICON，局部变换 scale(0.32) translate(-512 -512)）。 */
    ANGER,

    /** 录制中：方形点。 */
    SQUARE,

    /** 无障碍断开：虚线空环。 */
    DASHED_RING,

    /** 起跑：推进箭头。 */
    CHEVRON,

    /** 起跑：火苗。 */
    FLAME,

    /** 起跑、远眺：火箭。 */
    ROCKET,

    /** 思考：省略号。 */
    ELLIPSIS,

    /** 思考：灯泡。 */
    BULB,

    /** 思考：月亮。 */
    MOON,

    /** 思考：沙漏。 */
    HOURGLASS,

    /** 思考：星光。 */
    SPARK,

    /** 思考偷瞄、视觉定睛：眼睛。 */
    EYE,

    /** 手势：指针。 */
    CURSOR,

    /** 手势：左右拖拽箭头。 */
    DRAG,

    /** 手势确认：对勾。 */
    CHECK,

    /** 文本：文字行。 */
    LINES,

    /** 文本：铅笔。 */
    PENCIL,

    /** 文本输入：文本光标。 */
    IBEAM,

    /** 视觉扫视：放大镜。 */
    LENS,

    /** 视觉细看：扫描框。 */
    SCAN,

    /** 应用：应用格。 */
    GRID,

    /** 应用：窗口。 */
    WINDOW,

    /** 整理：漏斗。 */
    FUNNEL,

    /** 整理：风。 */
    WIND,
}

/** 符号的一段绘制：填充（[strokeWidth] 为 0）或描边，[dash] 非空时为虚线描边。 */
internal data class BallGlyphPart(
    val path: Path,
    val strokeWidth: Float = 0f,
    val dash: List<Float> = emptyList(),
) {
    /**
     * 描边样式，构造时一次算好（含虚线效果）。绘制时直接复用，避免每帧创建描边与原生的虚线效果对象。
     * 填充段为 null。
     */
    val stroke: Stroke? =
        if (strokeWidth > 0f) {
            Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = if (dash.isEmpty()) null else PathEffect.dashPathEffect(dash.toFloatArray()),
            )
        } else {
            null
        }
}

/** 符号的完整几何：若干段 + 可选的局部变换（只有怒气纹使用）。 */
internal data class BallGlyph(
    val parts: List<BallGlyphPart>,
    val scale: Float = 1f,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
)

/**
 * 预解析缓存：路径数据是常量，解析一次即可。用 lazy 延后到首次使用，
 * 保证文件末尾的 SAT_* 常量已经初始化。
 */
private val GLYPH_CACHE: Map<BallFaceSymbol, BallGlyph> by lazy {
    mapOf(
        BallFaceSymbol.NONE to BallGlyph(emptyList()),
        BallFaceSymbol.QUESTION to BallGlyph(listOf(part(SAT_QUESTION, stroke = 46f), dot())),
        BallFaceSymbol.EXCLAIM to BallGlyph(listOf(part(SAT_EXCLAIM), dot())),
        BallFaceSymbol.PAUSE to BallGlyph(listOf(part(SAT_PAUSE))),
        BallFaceSymbol.HEART to BallGlyph(listOf(part(SAT_HEART))),
        BallFaceSymbol.ANGER to
            BallGlyph(listOf(part(SAT_VEIN_ICON)), scale = 0.32f, translateX = -512f, translateY = -512f),
        BallFaceSymbol.SQUARE to BallGlyph(listOf(part(roundedRect(-62f, -62f, 124f, 124f, 28f)))),
        BallFaceSymbol.DASHED_RING to
            BallGlyph(listOf(part(circle(0f, 0f, 64f), stroke = 28f, dash = listOf(34f, 26f)))),
        BallFaceSymbol.CHEVRON to BallGlyph(listOf(part(SAT_CHEVRON))),
        BallFaceSymbol.FLAME to BallGlyph(listOf(part(SAT_FLAME))),
        BallFaceSymbol.ROCKET to BallGlyph(listOf(part(SAT_ROCKET))),
        BallFaceSymbol.ELLIPSIS to
            BallGlyph(listOf(part(circle(-54f, 0f, 16f) + " " + circle(0f, 0f, 16f) + " " + circle(54f, 0f, 16f)))),
        BallFaceSymbol.BULB to BallGlyph(listOf(part(SAT_BULB))),
        BallFaceSymbol.MOON to BallGlyph(listOf(part(SAT_MOON))),
        BallFaceSymbol.HOURGLASS to BallGlyph(listOf(part(SAT_HOURGLASS))),
        BallFaceSymbol.SPARK to BallGlyph(listOf(part(SAT_SPARK))),
        BallFaceSymbol.EYE to BallGlyph(listOf(part(SAT_EYE_OUTLINE, stroke = 16f), part(circle(0f, 0f, 22f)))),
        BallFaceSymbol.CURSOR to BallGlyph(listOf(part(SAT_CURSOR))),
        BallFaceSymbol.DRAG to BallGlyph(listOf(part(SAT_DRAG, stroke = 16f))),
        BallFaceSymbol.CHECK to BallGlyph(listOf(part(SAT_CHECK, stroke = 26f))),
        BallFaceSymbol.LINES to BallGlyph(listOf(part(SAT_LINES))),
        BallFaceSymbol.PENCIL to BallGlyph(listOf(part(SAT_PENCIL))),
        BallFaceSymbol.IBEAM to BallGlyph(listOf(part(SAT_IBEAM))),
        BallFaceSymbol.LENS to BallGlyph(listOf(part(SAT_LENS, stroke = 20f))),
        BallFaceSymbol.SCAN to BallGlyph(listOf(part(SAT_SCAN, stroke = 18f))),
        BallFaceSymbol.GRID to BallGlyph(listOf(part(SAT_GRID))),
        BallFaceSymbol.WINDOW to BallGlyph(listOf(part(SAT_WINDOW, stroke = 16f))),
        BallFaceSymbol.FUNNEL to BallGlyph(listOf(part(SAT_FUNNEL))),
        BallFaceSymbol.WIND to BallGlyph(listOf(part(SAT_WIND, stroke = 18f))),
    )
}

/** 符号 → 几何（缓存；不可变）。 */
internal fun ballGlyphOf(symbol: BallFaceSymbol): BallGlyph = GLYPH_CACHE.getValue(symbol)

/** 问号、感叹号下方的圆点（卫星上的第二笔）。 */
private fun dot(): BallGlyphPart = part(circle(0f, 70f, 26f))

private fun part(
    d: String,
    stroke: Float = 0f,
    dash: List<Float> = emptyList(),
): BallGlyphPart = BallGlyphPart(path = parseSvgPath(d), strokeWidth = stroke, dash = dash)

private fun parseSvgPath(d: String): Path = PathParser().parsePathString(d).toPath()

/** 圆（SVG 两段圆弧）。 */
private fun circle(
    cx: Float,
    cy: Float,
    r: Float,
): String = "M$cx ${cy - r} A$r $r 0 1 0 $cx ${cy + r} A$r $r 0 1 0 $cx ${cy - r} Z"

/** 圆角矩形（与设计稿 roundedRectPath 同序：上、右、下、左四边）。 */
private fun roundedRect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    r: Float,
): String =
    "M${x + r} $y H${x + w - r} A$r $r 0 0 1 ${x + w} ${y + r} V${y + h - r} " +
        "A$r $r 0 0 1 ${x + w - r} ${y + h} H${x + r} A$r $r 0 0 1 $x ${y + h - r} " +
        "V${y + r} A$r $r 0 0 1 ${x + r} $y Z"

private const val SAT_QUESTION = "M-34 -26 C-34 -72 34 -72 34 -26 C34 -4 6 4 0 28"
private val SAT_EXCLAIM = roundedRect(-25f, -88f, 50f, 120f, 25f)
private val SAT_PAUSE = roundedRect(-54f, -70f, 44f, 140f, 18f) + " " + roundedRect(10f, -70f, 44f, 140f, 18f)
private const val SAT_HEART =
    "M0 78 C-40 48 -86 12 -86 -30 C-86 -64 -48 -78 -20 -60 " +
        "C-8 -52 -2 -42 0 -34 C2 -42 8 -52 20 -60 C48 -78 86 -64 86 -30 " +
        "C86 12 40 48 0 78 Z"
private const val SAT_CHEVRON =
    "M-62 -38 L0 -78 L62 -38 L62 -6 L0 -46 L-62 -6 Z " +
        "M-62 18 L0 -22 L62 18 L62 50 L0 10 L-62 50 Z"
private const val SAT_FLAME =
    "M0 -88 C50 -36 62 4 30 36 C54 46 48 74 20 84 C-16 96 -54 68 -46 34 " +
        "C-40 8 -18 -12 -4 -32 C0 -16 6 -6 14 2 C22 -22 16 -54 0 -88 Z"
private const val SAT_ROCKET =
    "M0 -84 C28 -60 38 -26 36 10 C35 32 22 46 0 46 C-22 46 -35 32 -36 10 " +
        "C-38 -26 -28 -60 0 -84 Z " +
        "M-36 6 C-58 12 -70 30 -64 52 C-62 60 -54 62 -48 56 C-42 48 -40 36 -30 32 " +
        "C-34 24 -36 16 -36 6 Z " +
        "M36 6 C58 12 70 30 64 52 C62 60 54 62 48 56 C42 48 40 36 30 32 " +
        "C34 24 36 16 36 6 Z " +
        "M0 44 C16 54 24 70 16 84 C10 94 -4 96 -12 88 C-22 78 -20 58 0 44 Z"
private val SAT_BULB =
    "M0 -88 C-42 -88 -66 -60 -66 -26 C-66 -2 -52 12 -38 28 L-38 42 L38 42 " +
        "L38 28 C52 12 66 -2 66 -26 C66 -60 42 -88 0 -88 Z " +
        roundedRect(-22f, 52f, 44f, 26f, 8f)
private const val SAT_MOON =
    "M40 -76 C-30 -84 -88 -34 -76 24 C-68 62 -38 88 10 88 " +
        "C-24 76 -50 28 -36 -14 C-26 -44 4 -68 40 -76 Z"
private val SAT_HOURGLASS =
    roundedRect(-64f, -84f, 128f, 18f, 9f) + " " + roundedRect(-64f, 66f, 128f, 18f, 9f) +
        " M-50 -66 H50 L6 -6 H-6 Z M-6 6 H6 L50 66 H-50 Z"
private const val SAT_SPARK =
    "M0 -86 C10 -30 30 -10 86 0 C30 10 10 30 0 86 C-10 30 -30 10 -86 0 " +
        "C-30 -10 -10 -30 0 -86 Z"
private const val SAT_EYE_OUTLINE = "M-80 0 Q0 -70 80 0 Q0 70 -80 0 Z"
private const val SAT_CURSOR = "M-30 -84 L48 16 L10 20 L30 68 L12 76 L-8 28 L-30 50 Z"
private const val SAT_DRAG = "M-64 0 H64 M-64 0 L-38 -24 M-64 0 L-38 24 M64 0 L38 -24 M64 0 L38 24"
private const val SAT_CHECK = "M-70 2 L-20 52 L72 -52"
private val SAT_LINES =
    roundedRect(-66f, -58f, 132f, 20f, 10f) + " " + roundedRect(-66f, -14f, 132f, 20f, 10f) +
        " " + roundedRect(-66f, 30f, 96f, 20f, 10f)
private const val SAT_PENCIL = "M-64 34 L30 -60 L64 -26 L-30 68 Z M-64 34 L-30 68 L-64 68 Z"
private val SAT_IBEAM =
    roundedRect(-44f, -82f, 88f, 18f, 9f) + " " + roundedRect(-44f, 64f, 88f, 18f, 9f) +
        " " + roundedRect(-9f, -64f, 18f, 128f, 9f)
private val SAT_LENS = circle(0f, 0f, 50f) + " M36 36 L80 80"
private const val SAT_SCAN =
    "M-78 -78 H-36 M-78 -78 V-36 M78 -78 H36 M78 -78 V-36 " +
        "M-78 78 H-36 M-78 78 V36 M78 78 H36 M78 78 V36"
private val SAT_GRID =
    roundedRect(-64f, -64f, 54f, 54f, 14f) + " " + roundedRect(10f, -64f, 54f, 54f, 14f) +
        " " + roundedRect(-64f, 10f, 54f, 54f, 14f) + " " + roundedRect(10f, 10f, 54f, 54f, 14f)
private val SAT_WINDOW = roundedRect(-72f, -58f, 144f, 116f, 20f) + " M-72 -22 H72"
private const val SAT_FUNNEL = "M-66 -62 H66 L18 6 V52 L-18 70 V6 Z"
private const val SAT_WIND = "M-72 -24 C-30 -52 30 0 72 -26 M-72 26 C-30 0 30 52 72 24"

/** 失败用的 💢 怒气纹（用户提供，SAT_VEIN_ICON 原样保留）。 */
private const val SAT_VEIN_ICON =
    "M495.10528 251.93216l-23.808 7.30112 0.63488 31.744c0.95232 71.74272 " +
        "25.07776 136.4992 63.488 172.05376 37.77536 34.60096 81.9008 42.21952 144.75392 25.07776 " +
        "23.49056-6.66624 100.62848-43.17184 100.62848-47.93344 0-1.5872-5.71392-13.96736-12.6976-27.93472" +
        "l-12.6976-25.3952-18.09408 11.74528c-42.8544 27.29984-93.6448 44.4416-122.84928 40.94976" +
        "-51.744-6.03136-82.21824-60.63104-86.6624-155.22944-1.26976-26.34752-3.1744-40.31488-5.39648-39.99744" +
        "-1.90464 0.31744-14.2848 3.80928-27.29984 7.61856z" +
        "M273.21472 337.64096c-8.8896 11.1104-16.19072 21.5872-16.19072 22.85696 0 1.5872 " +
        "14.92096 10.15808 33.3312 19.36384 66.6624 33.96608 90.4704 60.94848 90.4704 102.21568 " +
        "0 26.66496-8.25216 43.80672-33.01248 68.2496-18.72896 18.72896-29.52192 26.03008-64.75776 44.12416" +
        "-27.936 14.2848-41.2672 22.53824-39.68 25.07776 2.2208 4.12672 29.20448 40.94976 31.10912 43.17184 " +
        "2.53952 2.22208 76.82048-39.04512 93.96224-52.3776 66.97984-51.42528 87.61344-128.24576 " +
        "53.01248-196.17792-8.57088-16.82432-34.9184-44.4416-56.50432-59.04384-23.808-16.19072" +
        "-64.44032-38.0928-70.47168-38.0928-2.85696 0-12.38016 9.20448-21.26848 20.6336z" +
        "M507.80288 576.3584c-17.14176 3.1744-47.93344 19.99744-61.26592 33.3312-17.77664 17.77536" +
        "-29.20448 37.77408-39.04512 66.6624-7.30112 20.95104-18.72896 81.26464-16.18944 83.80416 " +
        "1.26976 1.5872 54.28224 20.31616 54.59968 19.36384 0.31744-0.31744 2.53952-17.14176 5.39648-37.14048 " +
        "6.3488-44.75904 14.60224-67.93216 31.42656-86.6624 16.50688-19.0464 33.64864-25.71264 62.21824-23.808 " +
        "43.808 2.53952 77.1392 26.66496 114.91456 82.85312l16.50688 24.76032 8.57088-6.3488" +
        "c4.7616-3.49184 14.2848-12.06272 20.95104-19.0464 14.91968-15.23712 14.60224-16.82432-12.6976-53.01376" +
        "-33.01376-43.48928-71.10656-70.47168-117.13664-82.21696-17.77664-4.44416-50.7904-5.71392-68.2496-2.53952z"
