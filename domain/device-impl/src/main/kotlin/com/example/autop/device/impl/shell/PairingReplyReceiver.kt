/**
 * 配对发现通知的内联回填接收器：读取 RemoteInput 回填的 6 位配对码，
 * 用通知携带的发现端口直接完成无线调试配对并连接注入通道——全程不切屏。
 */
package com.example.autop.device.impl.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.example.autop.core.common.Outcome
import com.example.autop.core.platform.DataSchemaState
import com.example.autop.device.ShellInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 配对码内联回填接收器（manifest 注册）。 */
@AndroidEntryPoint
public class PairingReplyReceiver : BroadcastReceiver() {
    @Inject internal lateinit var shellInput: ShellInput

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!DataSchemaState.isReady()) return
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val code =
            RemoteInput
                .getResultsFromIntent(intent)
                ?.getCharSequence(PairingDiscoveryImpl.KEY_PAIR_CODE)
                ?.toString()
                ?.trim()
        if (port <= 0 || code.isNullOrEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = shellInput.pair(port, code)
                if (result is Outcome.Ok) shellInput.connect()
            } finally {
                pending.finish()
            }
        }
    }

    public companion object {
        /** 通知携带的发现端口。 */
        public const val EXTRA_PORT: String = "port"
    }
}
