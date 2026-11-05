/**
 * 支付确认测试页（docs/tech/10 WP5 验收，仅 debug 构建）：
 * 提供一个"确认付款"按钮，用 `tool.tap` 的坐标点击"确认付款"验证策略拦截
 * （docs/tech/06 §5 payment 类别 → `policy.blocked`）。
 */
package com.example.autop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** 测试页按钮与外层间距（dp）。 */
private const val PAGE_PADDING_DP: Int = 24

/** 调试验收用的假支付页。 */
public class PaymentTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(PAGE_PADDING_DP.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.payment_test_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(
                        onClick = { finish() },
                        modifier = Modifier.padding(top = PAGE_PADDING_DP.dp),
                    ) {
                        Text(text = stringResource(R.string.payment_test_confirm))
                    }
                }
            }
        }
    }
}
