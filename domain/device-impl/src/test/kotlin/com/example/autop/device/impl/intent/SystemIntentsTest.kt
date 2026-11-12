package com.example.autop.device.impl.intent

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.AlarmClock
import android.provider.Settings
import com.example.autop.device.core.IntentSpec
import com.example.autop.device.core.SettingsPage
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SystemIntentsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val intents = SystemIntents(context)

    @Test
    fun `alarm maps monday to sunday weekdays to calendar constants and keeps the dialog`() {
        val intent =
            intents
                .alarm(IntentSpec.Alarm(hour = 7, minute = 30, message = "起床", days = listOf(1, 7)))
                .intent
        assertThat(intent.action).isEqualTo(AlarmClock.ACTION_SET_ALARM)
        assertThat(intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1)).isEqualTo(7)
        assertThat(intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1)).isEqualTo(30)
        assertThat(intent.getStringExtra(AlarmClock.EXTRA_MESSAGE)).isEqualTo("起床")
        assertThat(intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)).containsExactly(2, 1).inOrder()
        assertThat(intent.hasExtra(AlarmClock.EXTRA_SKIP_UI)).isFalse()
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `timer sets the length and keeps the dialog`() {
        val intent = intents.timer(IntentSpec.Timer(seconds = 90, message = "煮蛋")).intent
        assertThat(intent.action).isEqualTo(AlarmClock.ACTION_SET_TIMER)
        assertThat(intent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1)).isEqualTo(90)
        assertThat(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true)).isFalse()
    }

    @Test
    fun `dial encodes the number into a tel uri and never calls`() {
        val intent = intents.dial(IntentSpec.Dial("+8613800138000")).intent
        assertThat(intent.action).isEqualTo(Intent.ACTION_DIAL)
        assertThat(intent.dataString).isEqualTo("tel:%2B8613800138000")
    }

    @Test
    fun `sms draft prefills the body without sending`() {
        val intent = intents.smsDraft(IntentSpec.SmsDraft(number = "10086", text = "你好")).intent
        assertThat(intent.action).isEqualTo(Intent.ACTION_SENDTO)
        assertThat(intent.dataString).isEqualTo("smsto:10086")
        assertThat(intent.getStringExtra("sms_body")).isEqualTo("你好")
    }

    @Test
    fun `share without app opens the system chooser`() {
        val prepared = intents.share(IntentSpec.Share(text = "分享一段话", app = null), appPackage = null)
        assertThat(prepared.intent.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertThat(prepared.handlerPackage).isNull()
    }

    @Test
    fun `share with app targets that package`() {
        val prepared = intents.share(IntentSpec.Share(text = "x", app = "微信"), appPackage = "com.example.chat")
        assertThat(prepared.intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(prepared.intent.`package`).isEqualTo("com.example.chat")
        assertThat(prepared.handlerPackage).isEqualTo("com.example.chat")
    }

    @Test
    fun `settings without any handler falls back to the main settings page`() {
        val prepared = intents.settings(IntentSpec.Settings(SettingsPage.WIFI, app = null), appPackage = null)
        assertThat(prepared.handlerPackage).isNull()
        assertThat(prepared.settingsActual).isEqualTo("home")
    }

    @Test
    fun `battery falls back to battery saver settings when power usage has no handler`() {
        val saver = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        shadowOf(context.packageManager).addResolveInfoForIntent(saver, resolveFor("com.android.settings"))
        val prepared = intents.settings(IntentSpec.Settings(SettingsPage.BATTERY, app = null), appPackage = null)
        assertThat(prepared.settingsActual).isEqualTo("battery_saver")
        assertThat(prepared.handlerPackage).isEqualTo("com.android.settings")
    }

    @Test
    fun `primary settings page with a handler is used without fallback`() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        shadowOf(context.packageManager).addResolveInfoForIntent(wifi, resolveFor("com.android.settings"))
        val prepared = intents.settings(IntentSpec.Settings(SettingsPage.WIFI, app = null), appPackage = null)
        assertThat(prepared.settingsActual).isNull()
        assertThat(prepared.handlerPackage).isEqualTo("com.android.settings")
    }

    private fun resolveFor(packageName: String): ResolveInfo =
        ResolveInfo().apply {
            activityInfo =
                ActivityInfo().apply {
                    this.packageName = packageName
                    name = "$packageName.Main"
                    applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
                }
        }
}
