/**
 * 单一图标入口（docs/tech/08 §2，同 LimeTV `LimeIcons`）。
 *
 * UI 代码不直接引用 `Icons.*`，一律取这里的成员，便于统一风格与后续替换。
 * 导航图标 Outlined/Filled 成对提供（底栏未选中/选中）；返回用 AutoMirrored。
 */
package com.example.autop.core.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** 图标统一入口。 */
public object AutopIcons {
    // ── 导航（Outlined 未选中 / Filled 选中）────────────────────
    public val ChatBubbleOutline: ImageVector = Icons.Outlined.ChatBubbleOutline
    public val ChatBubble: ImageVector = Icons.Filled.ChatBubble
    public val EventRepeat: ImageVector = Icons.Outlined.EventRepeat
    public val EventRepeatFilled: ImageVector = Icons.Filled.EventRepeat
    public val Psychology: ImageVector = Icons.Outlined.Psychology
    public val PsychologyFilled: ImageVector = Icons.Filled.Psychology
    public val Person: ImageVector = Icons.Outlined.Person
    public val PersonFilled: ImageVector = Icons.Filled.Person

    // ── 通用动作 ───────────────────────────────────────────────
    public val Back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack
    public val Close: ImageVector = Icons.Outlined.Close
    public val Check: ImageVector = Icons.Outlined.Check
    public val ChevronRight: ImageVector = Icons.Outlined.ChevronRight
    public val Search: ImageVector = Icons.Outlined.Search
    public val Refresh: ImageVector = Icons.Outlined.Refresh
    public val Send: ImageVector = Icons.AutoMirrored.Outlined.Send
    public val Mic: ImageVector = Icons.Outlined.Mic
    public val Add: ImageVector = Icons.Outlined.Add
    public val Remove: ImageVector = Icons.Outlined.Remove
    public val MoreVert: ImageVector = Icons.Outlined.MoreVert
    public val Delete: ImageVector = Icons.Outlined.Delete
    public val Edit: ImageVector = Icons.Outlined.Edit
    public val Copy: ImageVector = Icons.Outlined.ContentCopy
    public val Settings: ImageVector = Icons.Outlined.Settings

    // ── 状态与内容 ─────────────────────────────────────────────
    public val Play: ImageVector = Icons.Outlined.PlayArrow
    public val Pause: ImageVector = Icons.Outlined.Pause
    public val Stop: ImageVector = Icons.Outlined.Stop

    /** 录制（实心圆点），与「添加」图标区分。 */
    public val Record: ImageVector = Icons.Filled.FiberManualRecord
    public val Block: ImageVector = Icons.Outlined.Block
    public val Schedule: ImageVector = Icons.Outlined.Schedule
    public val Error: ImageVector = Icons.Outlined.ErrorOutline
    public val CheckCircle: ImageVector = Icons.Outlined.CheckCircle
    public val RadioButtonUnchecked: ImageVector = Icons.Outlined.RadioButtonUnchecked
    public val Warning: ImageVector = Icons.Outlined.Warning
    public val Info: ImageVector = Icons.Outlined.Info
    public val Folder: ImageVector = Icons.Outlined.Folder
    public val Description: ImageVector = Icons.Outlined.Description

    // ── 工具类别（执行轨迹，docs/tech/08 §4.2，N178）──────────
    public val TouchApp: ImageVector = Icons.Outlined.TouchApp
    public val Keyboard: ImageVector = Icons.Outlined.Keyboard
    public val Swipe: ImageVector = Icons.Outlined.Swipe
    public val OpenWith: ImageVector = Icons.Outlined.OpenWith
    public val Launch: ImageVector = Icons.AutoMirrored.Outlined.Launch
    public val Link: ImageVector = Icons.Outlined.Link
    public val PhotoCamera: ImageVector = Icons.Outlined.PhotoCamera
    public val Visibility: ImageVector = Icons.Outlined.Visibility
    public val ListAlt: ImageVector = Icons.AutoMirrored.Outlined.ListAlt
    public val PhoneAndroid: ImageVector = Icons.Outlined.PhoneAndroid
    public val ContentPaste: ImageVector = Icons.Outlined.ContentPaste
    public val HelpOutline: ImageVector = Icons.AutoMirrored.Outlined.HelpOutline

    // ── 应用标识 ─────────────────────────────────────────────────

    /**
     * 轨道：启动图标的线条版（pathData 同 res/drawable/ic_launcher_foreground.xml）。
     * 悬浮球默认图标用；绘制时由 Icon 的 tint 上色，随主题变色（本体只是缺省色）。
     */
    public val Orbit: ImageVector = orbitIcon()

    private fun orbitIcon(): ImageVector {
        val line = SolidColor(Color(ORBIT_INK))
        val builder =
            ImageVector.Builder(
                name = "Orbit",
                defaultWidth = 108.dp,
                defaultHeight = 108.dp,
                viewportWidth = ORBIT_VIEWPORT,
                viewportHeight = ORBIT_VIEWPORT,
            )
        builder.addPath(
            pathData = PathParser().parsePathString(ORBIT_RING).toNodes(),
            fill = null,
            stroke = line,
            strokeLineWidth = ORBIT_STROKE,
            strokeLineCap = StrokeCap.Round,
        )
        builder.addPath(pathData = PathParser().parsePathString(ORBIT_STAR).toNodes(), fill = line)
        builder.addPath(pathData = PathParser().parsePathString(ORBIT_PLANET).toNodes(), fill = line)
        return builder.build()
    }

    /** 图标画布（108，同启动图标前景）。 */
    private const val ORBIT_VIEWPORT: Float = 108f

    /** 线宽（同启动图标前景 6.5/108）。 */
    private const val ORBIT_STROKE: Float = 6.5f

    /** 缺省线色（同启动图标 #17171A）。 */
    private const val ORBIT_INK: Long = 0xFF17171A

    /** 轨道环（斜椭圆）pathData。 */
    private const val ORBIT_RING: String = "M27.2,69.5 A31,13 -30 0 1 80.9,38.5 A31,13 -30 0 1 27.2,69.5"

    /** 中心恒星 pathData（整圆＝两段半弧）。 */
    private const val ORBIT_STAR: String = "M47.5,54 A6.5,6.5 0 1 1 60.5,54 A6.5,6.5 0 1 1 47.5,54 Z"

    /** 轨道上的行星 pathData。 */
    private const val ORBIT_PLANET: String = "M67.5,56 A5.5,5.5 0 1 1 78.5,56 A5.5,5.5 0 1 1 67.5,56 Z"
}
