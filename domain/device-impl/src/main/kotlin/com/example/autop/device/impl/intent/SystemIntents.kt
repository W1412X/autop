/**
 * 各 kind 的系统 Intent 构造（intent-tool §4）。只构造、不启动；启动由 [ActivityStarter] 完成。
 * 所有 Intent 加 FLAG_ACTIVITY_NEW_TASK。Intent 的 action、extras 与设置页映射都是平台常量。
 */
package com.example.autop.device.impl.intent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import com.example.autop.device.core.IntentSpec
import com.example.autop.device.core.SettingsPage
import com.example.autop.device.impl.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 构造结果。[handlerPackage] 是系统解析出的处理者包名（分享指定应用时为该应用）；
 * [settingsActual] 只在设置页回退时非空，记录实际打开的页面 id。
 */
public data class PreparedIntent(
    val intent: Intent,
    val handlerPackage: String?,
    val settingsActual: String?,
)

/** 系统 Intent 工厂。 */
@Singleton
public class SystemIntents
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** 闹钟：不设 EXTRA_SKIP_UI，用户能在时钟界面看到设置内容。 */
        public fun alarm(spec: IntentSpec.Alarm): PreparedIntent {
            val intent =
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, spec.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, spec.minute)
                    .apply {
                        spec.message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                        if (spec.days.isNotEmpty()) {
                            putIntegerArrayListExtra(
                                AlarmClock.EXTRA_DAYS,
                                toCalendarDays(spec.days),
                            )
                        }
                    }
            return prepared(intent)
        }

        /** 计时器：EXTRA_SKIP_UI=false，让用户确认计时器界面。 */
        public fun timer(spec: IntentSpec.Timer): PreparedIntent {
            val intent =
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, spec.seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    .apply { spec.message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
            return prepared(intent)
        }

        /** 日程：只打开预填的新建界面，不写入日历。 */
        public fun calendar(spec: IntentSpec.Calendar): PreparedIntent {
            val intent =
                Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, spec.beginMs)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, spec.endMs)
                    .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, spec.allDay)
                    .putExtra(CalendarContract.Events.TITLE, spec.title)
                    .apply {
                        spec.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                        spec.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
                    }
            return prepared(intent)
        }

        /** 拨号盘：ACTION_DIAL，不拨出，不需要 CALL_PHONE。 */
        public fun dial(spec: IntentSpec.Dial): PreparedIntent {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(spec.number)))
            return prepared(intent)
        }

        /** 短信草稿：SENDTO 预填，不发送，不需要 SEND_SMS。 */
        public fun smsDraft(spec: IntentSpec.SmsDraft): PreparedIntent {
            val intent =
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(spec.number.orEmpty())))
                    .putExtra("sms_body", spec.text)
            return prepared(intent)
        }

        /** 分享文字：[appPackage] 为空时弹系统分享面板。 */
        public fun share(
            spec: IntentSpec.Share,
            appPackage: String?,
        ): PreparedIntent {
            val send =
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, spec.text)
            if (appPackage != null) {
                return PreparedIntent(prepare(send.setPackage(appPackage)), appPackage, null)
            }
            val chooser = Intent.createChooser(send, context.getString(R.string.intent_share_chooser_title))
            return PreparedIntent(prepare(chooser), null, null)
        }

        /**
         * 设置页：按回退链找第一个有处理者的 Intent；首选不可用时记录实际页面（[PreparedIntent.settingsActual]）。
         * 最后一步 `ACTION_SETTINGS` 也没有处理者时 [PreparedIntent.handlerPackage] 为 null，由调用方报错。
         */
        public fun settings(
            spec: IntentSpec.Settings,
            appPackage: String?,
        ): PreparedIntent {
            val chain = settingsChain(spec.page, appPackage)
            val chosen = chain.firstOrNull { handlerOf(it.intent) != null }
            if (chosen != null) {
                val actual = if (chosen.primary) null else chosen.label
                return PreparedIntent(prepare(chosen.intent), handlerOf(chosen.intent), actual)
            }
            val home = Intent(Settings.ACTION_SETTINGS)
            val actual = if (spec.page == SettingsPage.HOME) null else SettingsPage.HOME.id
            return PreparedIntent(prepare(home), handlerOf(home), actual)
        }

        /** 处理者包名；没有处理者返回 null。 */
        public fun handlerOf(intent: Intent): String? =
            context.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName

        private fun prepared(intent: Intent): PreparedIntent = PreparedIntent(prepare(intent), handlerOf(intent), null)

        private fun prepare(intent: Intent): Intent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** 模型周一到周日为 1–7，系统 Calendar 为周日=1 … 周六=7。 */
        private fun toCalendarDays(days: List<Int>): ArrayList<Int> =
            ArrayList(days.map { it % CALENDAR_DAYS_IN_WEEK + 1 })

        private fun settingsChain(
            page: SettingsPage,
            appPackage: String?,
        ): List<ChainEntry> {
            val primary = ChainEntry(primaryIntent(page, appPackage), page.id, primary = true)
            return if (page == SettingsPage.BATTERY) {
                listOf(
                    primary,
                    ChainEntry(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), BATTERY_SAVER_LABEL, primary = false),
                )
            } else {
                listOf(primary)
            }
        }

        /** 页面 → 平台 action（平台常量，intent-tool §4.8）；每个 [SettingsPage] 都必须有一项。 */
        private fun primaryIntent(
            page: SettingsPage,
            appPackage: String?,
        ): Intent {
            val intent = Intent(SETTINGS_ACTIONS.getValue(page))
            if (page ==
                SettingsPage.APP_NOTIFICATIONS
            ) {
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, appPackage.orEmpty())
            }
            return intent
        }

        /** 设置页回退链中的一项。 */
        private data class ChainEntry(
            val intent: Intent,
            val label: String,
            val primary: Boolean,
        )

        private companion object {
            const val CALENDAR_DAYS_IN_WEEK = 7
            const val BATTERY_SAVER_LABEL = "battery_saver"

            /** 各设置页的首选 action（平台常量）。 */
            val SETTINGS_ACTIONS: Map<SettingsPage, String> =
                mapOf(
                    SettingsPage.HOME to Settings.ACTION_SETTINGS,
                    SettingsPage.WIFI to Settings.ACTION_WIFI_SETTINGS,
                    SettingsPage.BLUETOOTH to Settings.ACTION_BLUETOOTH_SETTINGS,
                    SettingsPage.MOBILE_NETWORK to Settings.ACTION_WIRELESS_SETTINGS,
                    SettingsPage.AIRPLANE to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
                    SettingsPage.DISPLAY to Settings.ACTION_DISPLAY_SETTINGS,
                    SettingsPage.SOUND to Settings.ACTION_SOUND_SETTINGS,
                    SettingsPage.BATTERY to Intent.ACTION_POWER_USAGE_SUMMARY,
                    SettingsPage.LOCATION to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                    SettingsPage.DATE to Settings.ACTION_DATE_SETTINGS,
                    SettingsPage.LANGUAGE to Settings.ACTION_LOCALE_SETTINGS,
                    SettingsPage.STORAGE to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
                    SettingsPage.NFC to Settings.ACTION_NFC_SETTINGS,
                    SettingsPage.APP_NOTIFICATIONS to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                )
        }
    }
