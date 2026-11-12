/**
 * `intent` 工具的已校验参数模型（intent-tool §6.1）。纯 Kotlin，不依赖 Android。
 * 只有这里列出的事务类型；任意 Intent、组件名、extras 一律不接受。
 */
package com.example.autop.device.core

/** 一次 `intent` 调用的已校验参数。 */
public sealed interface IntentSpec {
    /** 闹钟；[days] 为 1–7（1=周一），空表示只响一次。 */
    public data class Alarm(
        val hour: Int,
        val minute: Int,
        val message: String?,
        val days: List<Int>,
    ) : IntentSpec

    /** 计时器；[seconds] 已确认在 1 到上限之间。 */
    public data class Timer(
        val seconds: Int,
        val message: String?,
    ) : IntentSpec

    /** 日程；时间已转换为毫秒（手机时区）。 */
    public data class Calendar(
        val title: String,
        val beginMs: Long,
        val endMs: Long,
        val allDay: Boolean,
        val location: String?,
        val description: String?,
    ) : IntentSpec

    /** 拨号盘（不拨出）。号码已去掉空格。 */
    public data class Dial(
        val number: String,
    ) : IntentSpec

    /** 短信草稿（不发送）。 */
    public data class SmsDraft(
        val number: String?,
        val text: String,
    ) : IntentSpec

    /** 分享文字；[app] 为空时弹系统分享面板。 */
    public data class Share(
        val text: String,
        val app: String?,
    ) : IntentSpec

    /** 导航目的地；[destination] 与 [lat]/[lng] 至少一个。 */
    public data class Navigate(
        val destination: String?,
        val lat: Double?,
        val lng: Double?,
        val mode: NavMode,
        val map: String?,
    ) : IntentSpec

    /** 设置页；[app] 只在 [SettingsPage.APP_NOTIFICATIONS] 时使用。 */
    public data class Settings(
        val page: SettingsPage,
        val app: String?,
    ) : IntentSpec
}

/** 出行方式。[id] 是给模型和规则表用的稳定字符串。 */
public enum class NavMode(
    public val id: String,
) {
    DRIVE("drive"),
    TRANSIT("transit"),
    WALK("walk"),
    RIDE("ride"),
}

/** 允许打开的设置页（不含无障碍、开发者选项、权限等安全相关页面）。 */
public enum class SettingsPage(
    public val id: String,
) {
    HOME("home"),
    WIFI("wifi"),
    BLUETOOTH("bluetooth"),
    MOBILE_NETWORK("mobile_network"),
    AIRPLANE("airplane"),
    DISPLAY("display"),
    SOUND("sound"),
    BATTERY("battery"),
    LOCATION("location"),
    DATE("date"),
    LANGUAGE("language"),
    STORAGE("storage"),
    NFC("nfc"),
    APP_NOTIFICATIONS("app_notifications"),
}

/** 来自配置的数值上限（`device.intent.*`）。 */
public data class IntentLimits(
    val timerMaxSeconds: Int,
    val calendarDefaultMinutes: Int,
    val textMaxChars: Int,
)

/** 参数校验失败的原因；模板 `device.intent.invalid` 按它选文字。 */
public enum class IntentInvalidReason {
    MISSING_KIND,
    UNKNOWN_KIND,
    ALARM_NEEDS_HOUR,
    BAD_TIME,
    BAD_DAYS,
    TIMER_NEEDS_SECONDS,
    TIMER_RANGE,
    CALENDAR_NEEDS_TITLE,
    CALENDAR_NEEDS_BEGIN,
    BAD_BEGIN,
    BAD_END,
    END_BEFORE_BEGIN,
    DIAL_NEEDS_NUMBER,
    BAD_NUMBER,
    SMS_NEEDS_TEXT,
    SHARE_NEEDS_TEXT,
    TEXT_TOO_LONG,
    NAV_NEEDS_TARGET,
    COORD_PAIR,
    COORD_RANGE,
    BAD_MODE,
    BAD_MAP,
    SETTINGS_NEEDS_PAGE,
    BAD_PAGE,
    SETTINGS_NEEDS_APP,
}

/**
 * 校验失败详情。[value] 是用户给的非法值；[detail] 是可选值列表（逗号分隔）或数值上限。
 */
public data class IntentInvalid(
    val reason: IntentInvalidReason,
    val value: String? = null,
    val detail: String? = null,
)

/** 解析结果。 */
public sealed interface IntentParse {
    public data class Ok(
        val spec: IntentSpec,
    ) : IntentParse

    public data class Invalid(
        val error: IntentInvalid,
    ) : IntentParse
}

/** 事务的 kind 字符串（与工具参数 `kind` 的枚举一致）。 */
public val IntentSpec.kind: String
    get() =
        when (this) {
            is IntentSpec.Alarm -> "alarm"
            is IntentSpec.Timer -> "timer"
            is IntentSpec.Calendar -> "calendar"
            is IntentSpec.Dial -> "dial"
            is IntentSpec.SmsDraft -> "sms_draft"
            is IntentSpec.Share -> "share"
            is IntentSpec.Navigate -> "navigate"
            is IntentSpec.Settings -> "settings"
        }
