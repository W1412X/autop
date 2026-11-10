/**
 * [ReplayStateProbe] 实现（agent-harness-overhaul WP2.8）：按录制时的观测方式采样当前屏幕
 * （a11y 或 visual），供回放前比对与 [ActionEpilogue] 的等待稳定采样；不写 Run 的观测历史。
 */
package com.example.autop.device.impl.observation

import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayStateSample
import com.example.autop.core.common.AppError
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.device.ObserveMode
import javax.inject.Inject
import javax.inject.Singleton

/** 屏幕语义采样：不进差量基线、不动 Run 的观测历史，只读当前屏。 */
@Singleton
public class ReplayStateProbeImpl
    @Inject
    constructor(
        private val preparer: ScreenPreparer,
    ) : ReplayStateProbe {
        override suspend fun sample(
            observeMode: String,
            config: ConfigSnapshot,
        ): Outcome<ReplayStateSample> =
            when (observeMode) {
                ObserveMode.A11Y.id ->
                    when (val got = preparer.a11y(config, listOf(ObserveMode.A11Y))) {
                        is Outcome.Err -> got
                        is Outcome.Ok -> Outcome.Ok(sampleOf(got.value.serialized, ObserveMode.A11Y, config))
                    }
                ObserveMode.VISUAL.id ->
                    when (val got = preparer.visual(config, listOf(ObserveMode.VISUAL))) {
                        is Outcome.Err -> got
                        is Outcome.Ok -> Outcome.Ok(sampleOf(got.value.serialized, ObserveMode.VISUAL, config))
                    }
                else ->
                    Outcome.Err(
                        AppError(CommonErrorCodes.INVALID_ARGUMENT, "unsupported replay observe mode: $observeMode"),
                    )
            }
    }
