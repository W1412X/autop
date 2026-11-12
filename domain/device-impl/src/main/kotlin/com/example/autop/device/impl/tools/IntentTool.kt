/**
 * `tool.intent`（intent-tool §6.2）：用系统意图办事（闹钟、计时器、日程、拨号、短信草稿、分享、导航、设置页）。
 * 只交办，不声称办成；导航在国内外地图应用之间按设置与请求选择，且只使用已安装的应用。
 */
package com.example.autop.device.impl.tools

import android.content.Intent
import android.net.Uri
import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.AppInfo
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.NavigationApp
import com.example.autop.device.NavigationApps
import com.example.autop.device.ScreenReader
import com.example.autop.device.core.AppMatch
import com.example.autop.device.core.AppMatcher
import com.example.autop.device.core.IntentInvalid
import com.example.autop.device.core.IntentLimits
import com.example.autop.device.core.IntentParse
import com.example.autop.device.core.IntentSpec
import com.example.autop.device.core.IntentSpecParser
import com.example.autop.device.core.NavChoice
import com.example.autop.device.core.NavReason
import com.example.autop.device.core.NavigationChooser
import com.example.autop.device.core.SettingsPage
import com.example.autop.device.core.kind
import com.example.autop.device.core.navigation.NavigationRules
import com.example.autop.device.core.navigation.NavigationUriBuilder
import com.example.autop.device.impl.AppIdentity
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.intent.ActivityStarter
import com.example.autop.device.impl.intent.NavigationAppsRulesLoader
import com.example.autop.device.impl.intent.PreparedIntent
import com.example.autop.device.impl.intent.SystemIntents
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷：已校验参数、原始参数（回放用）。 */
private data class IntentAction(
    val spec: IntentSpec,
    val args: JsonObject,
)

/** 系统事务工具。 */
@Suppress("LongParameterList") // 装配依赖多（intent-tool §6.2）；与其它设备工具同口径（N114）。
@Singleton
public class IntentTool
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val apps: AppLauncher,
        private val navigationApps: NavigationApps,
        private val rulesLoader: NavigationAppsRulesLoader,
        private val intents: SystemIntents,
        private val starter: ActivityStarter,
        private val subjects: PolicySubjectBuilder,
        private val epilogue: ActionEpilogue,
        private val prompts: PromptRepository,
        private val appIdentity: AppIdentity,
        private val logger: Logger,
    ) : ScreenActionTool {
        override val id: String = ID

        override val spec: ToolSpec = DeviceToolSpecs.intent

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        /** `map` 的枚举与说明取自导航规则表（§5.4）。 */
        override fun specFor(ctx: ToolContext): ToolSpec {
            val rules = rulesLoader.get()
            val mapList = rules.apps.joinToString("、") { "${it.id}=${it.label}" }
            return DeviceToolSpecs.intentFor(rules.apps.map { it.id }, mapList)
        }

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val rules = rulesLoader.get()
            val parsed =
                IntentSpecParser.parse(
                    args,
                    limitsOf(ctx),
                    rules.apps.map { it.id }.toSet(),
                    ZoneId.systemDefault(),
                )
            val intent =
                when (parsed) {
                    is IntentParse.Invalid -> return invalid(parsed.error)
                    is IntentParse.Ok -> parsed.spec
                }
            // N193 空树降级：系统意图不依赖节点树，空树时仍可办事。
            val snap =
                when (val got = screenReader.snapshotOrDegraded()) {
                    is Outcome.Ok -> got.value
                    is Outcome.Err -> return got
                }
            val extra =
                buildMap {
                    put(EXTRA_KIND, intent.kind)
                    pageOf(intent)?.let { put(EXTRA_PAGE, it) }
                    if (intent is IntentSpec.Uri) {
                        put(EXTRA_URI, intent.uri)
                        put(EXTRA_SCHEME, intent.uri.substringBefore(':', missingDelimiterValue = "").lowercase())
                    }
                }
            val subject = subjects.build(ID, snap, null, extra, ctx.config)
            return Outcome.Ok(PreparedAction(subject, IntentAction(intent, args)))
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val payload = action.payload as IntentAction
            val pre = epilogue.preSample(ctx)
            return when (val intent = payload.spec) {
                is IntentSpec.Navigate -> performNavigate(intent, payload, pre, ctx)
                is IntentSpec.Alarm,
                is IntentSpec.Timer,
                is IntentSpec.Calendar,
                is IntentSpec.Dial,
                is IntentSpec.SmsDraft,
                is IntentSpec.Share,
                is IntentSpec.Settings,
                is IntentSpec.Uri,
                -> performSystem(intent, payload, pre, ctx)
            }
        }

        /** 非导航事务：解析指定应用、构造 Intent、检查处理者、启动。 */
        private suspend fun performSystem(
            intent: IntentSpec,
            action: IntentAction,
            pre: ReplayStateSample?,
            ctx: ToolContext,
        ): ToolOutput {
            val appPackage =
                when (val resolved = resolveNamedApp(intent)) {
                    is Outcome.Err -> return errorOutput(resolved.error)
                    is Outcome.Ok -> resolved.value
                }
            val prepared = buildSystemIntent(intent, appPackage)
            // 分享到选择器时由选择器负责，不做处理者检查。
            if (prepared.intent.action != Intent.ACTION_CHOOSER && prepared.handlerPackage == null) {
                return noHandlerOutput(intent.kind)
            }
            starter.start(prepared.intent, intent.kind, DeviceErrorCodes.LAUNCH_FAILED)?.let { return errorOutput(it) }
            val lines =
                buildList {
                    add(prompts.text(PROMPT_RESULT, resultVars(intent)))
                    prepared.settingsActual?.let { add(prompts.text(PROMPT_SETTINGS_FALLBACK, mapOf("actual" to it))) }
                }
            return finish(lines.joinToString("\n"), action, pre, ctx)
        }

        /** 导航：按规则表与偏好选应用；每次询问时通过 askUser 让用户选。 */
        private suspend fun performNavigate(
            nav: IntentSpec.Navigate,
            action: IntentAction,
            pre: ReplayStateSample?,
            ctx: ToolContext,
        ): ToolOutput {
            val rules = rulesLoader.get()
            val installed = navigationApps.installed()
            val preferred = ctx.config[DeviceConfig.INTENT].preferredMap
            warnIfPreferredMissing(preferred, installed)
            return when (val choice = NavigationChooser.choose(nav.map, preferred, installed, nav.mode, rules)) {
                is NavChoice.Use ->
                    launchNavigation(
                        choice.app,
                        navChoiceText(choice.app.label, choice.reason),
                        nav,
                        action,
                        pre,
                        ctx,
                        rules,
                    )
                is NavChoice.Ask -> askAndLaunch(choice.options, nav, action, pre, ctx, rules)
                is NavChoice.RequestedNotInstalled -> requestedMissing(choice.requestedId, installed)
                NavChoice.NoneInstalled -> launchGeo(nav, action, pre, ctx)
            }
        }

        /** 每次询问：超时、没有交互端口或映射不上时按优先级第一项回退，并在结果里写明。 */
        private suspend fun askAndLaunch(
            options: List<NavigationApp>,
            nav: IntentSpec.Navigate,
            action: IntentAction,
            pre: ReplayStateSample?,
            ctx: ToolContext,
            rules: NavigationRules,
        ): ToolOutput {
            val answer =
                ctx.askUser(
                    question = prompts.text(PROMPT_ASK_MAP),
                    options = options.map { it.label },
                    timeoutMs = ctx.config[DeviceConfig.INTENT].askTimeoutMs,
                )
            val picked =
                (answer as? Outcome.Ok)?.value?.let { label -> options.firstOrNull { it.label == label } }
            return if (picked != null) {
                launchNavigation(picked, navChoiceText(picked.label, null, ask = true), nav, action, pre, ctx, rules)
            } else {
                val fallback = options.first()
                val note = navChoiceText(fallback.label, NavReason.AUTO, askFallback = true)
                launchNavigation(fallback, note, nav, action, pre, ctx, rules)
            }
        }

        /** 启动指定的导航应用；启动失败按 ActivityStarter 分流，不自动换应用（§5.6）。 */
        private suspend fun launchNavigation(
            app: NavigationApp,
            note: String,
            nav: IntentSpec.Navigate,
            action: IntentAction,
            pre: ReplayStateSample?,
            ctx: ToolContext,
            rules: NavigationRules,
        ): ToolOutput {
            val rule = rules.apps.firstOrNull { it.id == app.id } ?: return noHandlerOutput(nav.kind)
            val uri = NavigationUriBuilder.build(rule, nav, appIdentity.packageName) ?: return noHandlerOutput(nav.kind)
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .setPackage(app.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            starter.start(intent, "navigate:${app.id}", DeviceErrorCodes.LAUNCH_FAILED)?.let { return errorOutput(it) }
            return finishNavigation(nav, note, action, pre, ctx)
        }

        /** 没有任何导航应用：用 `geo:` 兜底，结果说明它只显示位置。 */
        private suspend fun launchGeo(
            nav: IntentSpec.Navigate,
            action: IntentAction,
            pre: ReplayStateSample?,
            ctx: ToolContext,
        ): ToolOutput {
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(NavigationUriBuilder.geo(nav)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intents.handlerOf(intent) == null) return noHandlerOutput(nav.kind)
            starter.start(intent, "navigate:geo", DeviceErrorCodes.LAUNCH_FAILED)?.let { return errorOutput(it) }
            return finishNavigation(nav, navChoiceText(null, null, geo = true), action, pre, ctx)
        }

        private suspend fun finishNavigation(
            nav: IntentSpec.Navigate,
            note: String,
            action: IntentAction,
            pre: ReplayStateSample?,
            ctx: ToolContext,
        ): ToolOutput {
            val result = prompts.text(PROMPT_RESULT, resultVars(nav))
            return finish(listOf(result, note).joinToString("\n"), action, pre, ctx)
        }

        /** 收尾：固定等待 + 回放记录（tool_optimize §10.3，不再有前台行）。 */
        private suspend fun finish(
            resultText: String,
            action: IntentAction,
            pre: ReplayStateSample?,
            ctx: ToolContext,
        ): ToolOutput =
            epilogue.finish(ctx, resultText, waitMsOf(action.args), ReplayInput(ID, action.args, gesture = null), pre)

        /** 用户指定的导航应用未安装：列出已安装的应用。 */
        private fun requestedMissing(
            requestedId: String,
            installed: List<NavigationApp>,
        ): ToolOutput {
            val label = navigationApps.known().firstOrNull { it.id == requestedId }?.label ?: requestedId
            val vars =
                buildMap {
                    put("map", label)
                    if (installed.isEmpty()) {
                        put(
                            "none",
                            ONE,
                        )
                    } else {
                        put("installed", installed.joinToString("、") { it.label })
                    }
                }
            return toolError(
                DeviceErrorCodes.APP_NOT_FOUND,
                "requested map $requestedId is not installed",
                prompts.text(PROMPT_MAP_NOT_INSTALLED, vars),
            )
        }

        private fun warnIfPreferredMissing(
            preferred: String,
            installed: List<NavigationApp>,
        ) {
            if (preferred == NavigationChooser.PREFERENCE_AUTO || preferred == NavigationChooser.PREFERENCE_ASK) return
            if (installed.none { it.id == preferred }) logger.w(TAG, "preferred_map_unavailable", "id" to preferred)
        }

        /** 指定 share 的 app 或 settings 的 app_notifications 应用时解析包名（§4.7）。 */
        private suspend fun resolveNamedApp(intent: IntentSpec): Outcome<String?> {
            val query =
                when (intent) {
                    is IntentSpec.Share -> intent.app
                    is IntentSpec.Settings -> intent.app.takeIf { intent.page == SettingsPage.APP_NOTIFICATIONS }
                    is IntentSpec.Uri,
                    is IntentSpec.Alarm,
                    is IntentSpec.Timer,
                    is IntentSpec.Calendar,
                    is IntentSpec.Dial,
                    is IntentSpec.SmsDraft,
                    is IntentSpec.Navigate,
                    -> null
                } ?: return Outcome.Ok(null)
            return when (val match = AppMatcher.match(query, apps.listApps(query))) {
                is AppMatch.Found -> Outcome.Ok(match.app.packageName)
                is AppMatch.NotFound -> Outcome.Err(appNotFound(query))
                is AppMatch.Ambiguous -> Outcome.Err(ambiguousApp(query, match.candidates))
            }
        }

        private fun appNotFound(query: String): AppError =
            AppError(
                code = DeviceErrorCodes.APP_NOT_FOUND,
                message = "no app matches $query",
                hint = prompts.text(PROMPT_APP_CANDIDATES, mapOf("query" to query, "notFound" to ONE)),
            )

        private fun ambiguousApp(
            query: String,
            candidates: List<AppInfo>,
        ): AppError =
            AppError(
                code = DeviceErrorCodes.INVALID_ARGUMENT,
                message = "app name $query matches several apps",
                hint =
                    prompts.text(
                        PROMPT_APP_CANDIDATES,
                        mapOf(
                            "query" to query,
                            "ambiguous" to ONE,
                            "candidates" to candidates.joinToString("\n") { "${it.label} ${it.packageName}" },
                        ),
                    ),
            )

        private fun buildSystemIntent(
            intent: IntentSpec,
            appPackage: String?,
        ): PreparedIntent =
            when (intent) {
                is IntentSpec.Alarm -> intents.alarm(intent)
                is IntentSpec.Timer -> intents.timer(intent)
                is IntentSpec.Calendar -> intents.calendar(intent)
                is IntentSpec.Dial -> intents.dial(intent)
                is IntentSpec.SmsDraft -> intents.smsDraft(intent)
                is IntentSpec.Share -> intents.share(intent, appPackage)
                is IntentSpec.Settings -> intents.settings(intent, appPackage)
                is IntentSpec.Uri -> uriIntent(intent)
                is IntentSpec.Navigate -> error("navigate is launched by performNavigate")
            }

        /** 打开链接或 deep link（原 tool.open_uri，tool_optimize §10.3）。 */
        private fun uriIntent(spec: IntentSpec.Uri): PreparedIntent {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(spec.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PreparedIntent(intent, intents.handlerOf(intent), null)
        }

        /** 结果行的模板变量（§9.1 device.intent.result 的 detail 规则）。 */
        private fun resultVars(intent: IntentSpec): Map<String, String> =
            when (intent) {
                is IntentSpec.Alarm -> mapOf("alarm" to ONE, "detail" to alarmDetail(intent))
                is IntentSpec.Timer -> mapOf("timer" to ONE, "detail" to "${intent.seconds}s")
                is IntentSpec.Calendar -> mapOf("calendar" to ONE, "detail" to intent.title)
                is IntentSpec.Dial -> mapOf("dial" to ONE, "detail" to intent.number)
                is IntentSpec.SmsDraft -> mapOf("smsDraft" to ONE, "detail" to intent.number.orEmpty())
                is IntentSpec.Share -> mapOf("share" to ONE, "detail" to intent.text.take(SHARE_DETAIL_CHARS))
                is IntentSpec.Navigate ->
                    mapOf(
                        "navigate" to ONE,
                        "detail" to (intent.destination ?: "${intent.lat},${intent.lng}"),
                    )
                is IntentSpec.Settings -> mapOf("settings" to ONE, "detail" to intent.page.id)
                is IntentSpec.Uri -> mapOf("uri" to ONE, "detail" to intent.uri)
            }

        private fun alarmDetail(alarm: IntentSpec.Alarm): String {
            val time = String.format(Locale.ROOT, "%02d:%02d", alarm.hour, alarm.minute)
            return if (alarm.days.isEmpty()) time else "$time days=${alarm.days.joinToString(",")}"
        }

        /** 导航选择说明（§5.2、§9.1 device.intent.navigate_choice）。 */
        private fun navChoiceText(
            app: String?,
            reason: NavReason?,
            ask: Boolean = false,
            askFallback: Boolean = false,
            geo: Boolean = false,
        ): String {
            val vars =
                buildMap {
                    app?.let { put("app", it) }
                    reason?.let { put(reasonFlag(it), ONE) }
                    if (ask) put("ask", ONE)
                    if (askFallback) put("askFallback", ONE)
                    if (geo) put("geo", ONE)
                }
            return prompts.text(PROMPT_NAV_CHOICE, vars)
        }

        private fun reasonFlag(reason: NavReason): String =
            when (reason) {
                NavReason.REQUESTED -> "requested"
                NavReason.PREFERRED -> "preferred"
                NavReason.AUTO -> "auto"
                NavReason.ONLY_ONE -> "onlyOne"
            }

        private fun invalid(error: IntentInvalid): Outcome.Err {
            val vars =
                buildMap {
                    put(reasonVar(error), ONE)
                    error.detail?.let { put("detail", it) }
                    error.value?.let { put("value", it) }
                }
            return Outcome.Err(
                AppError(
                    code = DeviceErrorCodes.INVALID_ARGUMENT,
                    message = "invalid intent arguments: ${error.reason.name}",
                    hint = prompts.text(PROMPT_INVALID, vars),
                ),
            )
        }

        private fun noHandlerOutput(kind: String): ToolOutput =
            toolError(
                DeviceErrorCodes.APP_NOT_FOUND,
                "no app can handle $kind",
                prompts.text(PROMPT_NO_HANDLER, mapOf("kind" to kind)),
            )

        private fun limitsOf(ctx: ToolContext): IntentLimits {
            val cfg = ctx.config[DeviceConfig.INTENT]
            return IntentLimits(
                timerMaxSeconds = cfg.timerMaxSeconds,
                calendarDefaultMinutes = cfg.calendarDefaultMinutes,
                textMaxChars = cfg.textMaxChars,
            )
        }

        private fun pageOf(intent: IntentSpec): String? = (intent as? IntentSpec.Settings)?.page?.id

        /** 参数名 `IntentInvalidReason` 的 camelCase 形式即模板变量名（如 `SMS_NEEDS_TEXT` → `smsNeedsText`）。 */
        private fun reasonVar(error: IntentInvalid): String =
            error.reason.name
                .lowercase()
                .split('_')
                .mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar { it.uppercase() } }
                .joinToString("")

        private companion object {
            const val ID = "tool.intent"
            const val TAG = "IntentTool"
            const val ONE = "1"
            const val EXTRA_KIND = "kind"
            const val EXTRA_URI = "uri"
            const val EXTRA_SCHEME = "scheme"
            const val EXTRA_PAGE = "page"
            const val SHARE_DETAIL_CHARS = 20
            const val PROMPT_INVALID = "device.intent.invalid"
            const val PROMPT_RESULT = "device.intent.result"
            const val PROMPT_NAV_CHOICE = "device.intent.navigate_choice"
            const val PROMPT_SETTINGS_FALLBACK = "device.intent.settings_fallback"
            const val PROMPT_ASK_MAP = "device.intent.ask_map"
            const val PROMPT_NO_HANDLER = "device.hint.intent_no_handler"
            const val PROMPT_APP_CANDIDATES = "device.hint.intent_app_candidates"
            const val PROMPT_MAP_NOT_INSTALLED = "device.hint.intent_map_not_installed"
        }
    }
