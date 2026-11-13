/**
 * [OptionSheet] 的单个选项。
 */
package com.example.autop.core.ui.component

/** 选项前置图标；由 core-ui 映射成具体图标，避免 ViewModel 依赖 Compose。 */
public enum class OptionIcon {
    EDIT,
    COPY,
    DELETE,
    MOVE,
    UP,
    DOWN,
    HISTORY,
    EXPORT,
    ADD,
    FOLDER,
    VIEW,
    RESTORE,
    RUN,
    TOGGLE,
    BREAKPOINT,
    FORK,
    RAW,
    TEMPLATE,
    AI,
    SAVE_TASK,
}

/** 选项语气：破坏性动作整行用 error 色。 */
public enum class OptionTone {
    NEUTRAL,
    DESTRUCTIVE,
}

/** 选项：稳定 [id] 用于选中判定，[label] 展示；[description]/[icon]/[tag] 均可省略。 */
public data class OptionItem(
    val id: String,
    val label: String,
    val description: String? = null,
    val icon: OptionIcon? = null,
    val tag: String? = null,
    val tone: OptionTone = OptionTone.NEUTRAL,
)
