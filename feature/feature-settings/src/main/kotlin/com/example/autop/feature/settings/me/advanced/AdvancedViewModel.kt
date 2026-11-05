/**
 * 高级配置页 ViewModel（docs/tech/08 §4.5"高级配置"）：
 * 按 [AllConfigKeys.ALL] 列出全部配置段（当前值/默认值，美化 JSON），
 * 编辑整段 JSON 片段经 [ConfigEditor.setOverride] 写用户覆盖；
 * 校验失败（`config.invalid`）显示错误且不生效。
 */
package com.example.autop.feature.settings.me.advanced

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.AllConfigKeys
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.ConfigKey
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.ConfigSection
import com.example.autop.core.config.ConfigSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

/** 编辑器瞬时状态（与配置快照组合成 [AdvancedUiState]）。 */
private data class EditorState(
    val path: String? = null,
    val text: String = "",
    val error: String? = null,
    val saving: Boolean = false,
)

@HiltViewModel
public class AdvancedViewModel
    @Inject
    constructor(
        private val configProvider: ConfigProvider,
        private val configEditor: ConfigEditor,
        private val dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val editor = MutableStateFlow(EditorState())

        public val uiState: StateFlow<AdvancedUiState> =
            combine(configProvider.observe(), editor) { snapshot, ed ->
                val items = AllConfigKeys.ALL.map { key -> itemOf(key, snapshot) }
                val groups =
                    items
                        .groupBy { it.path.substringBefore('.') }
                        .map { (domain, list) -> AdvancedGroup(domain = domain, items = list.sortedBy { it.path }) }
                        .sortedBy { it.domain }
                AdvancedUiState(
                    groups = groups,
                    editing = ed.path?.let { path -> items.firstOrNull { it.path == path } },
                    editText = ed.text,
                    editError = ed.error,
                    saving = ed.saving,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = AdvancedUiState(),
            )

        public fun onAction(action: AdvancedAction) {
            when (action) {
                is AdvancedAction.Open -> open(action.path)
                is AdvancedAction.SetEditText -> editor.value = editor.value.copy(text = action.text, error = null)
                AdvancedAction.Save -> save()
                AdvancedAction.ClearOverride -> clearOverride()
                AdvancedAction.CloseEditor -> editor.value = EditorState()
            }
        }

        private fun open(path: String) {
            val snapshot = configProvider.current()
            val key = AllConfigKeys.byPath(path) ?: return
            editor.value =
                EditorState(
                    path = path,
                    text = pretty(currentOf(key, snapshot)),
                    error = null,
                )
        }

        private fun save() {
            val state = editor.value
            val path = state.path ?: return
            viewModelScope.launch(dispatchers.io) {
                editor.value = state.copy(saving = true, error = null)
                val element =
                    try {
                        AutopJson.default.parseToJsonElement(state.text)
                    } catch (e: SerializationException) {
                        editor.value = editor.value.copy(saving = false, error = e.message ?: e.toString())
                        return@launch
                    }
                when (val result = configEditor.setOverride(path, element)) {
                    is Outcome.Ok -> editor.value = EditorState()
                    is Outcome.Err -> editor.value = editor.value.copy(saving = false, error = result.error.message)
                }
            }
        }

        private fun clearOverride() {
            val path = editor.value.path ?: return
            viewModelScope.launch(dispatchers.io) {
                configEditor.clearOverride(path)
                editor.value = EditorState()
            }
        }

        private fun itemOf(
            key: ConfigKey<*>,
            snapshot: ConfigSnapshot,
        ): AdvancedItem {
            val current = currentOf(key, snapshot)
            val default = defaultOf(key)
            return AdvancedItem(
                path = key.path,
                overridden = current != default,
                valueJson = pretty(current),
                defaultJson = pretty(default),
            )
        }

        /** 星投影键的取值与编码（泛型捕获后逐键处理）。 */
        private fun <T : ConfigSection> currentOf(
            key: ConfigKey<T>,
            snapshot: ConfigSnapshot,
        ): JsonElement = AutopJson.default.encodeToJsonElement(key.serializer, snapshot[key])

        private fun <T : ConfigSection> defaultOf(key: ConfigKey<T>): JsonElement =
            AutopJson.default.encodeToJsonElement(key.serializer, key.defaultInstance())

        private fun pretty(value: JsonElement): String =
            AutopJson.pretty.encodeToString(JsonElement.serializer(), value)
    }
