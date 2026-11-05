package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ONNX 图标分类引擎（vp9 §5.3）：未就绪回退口径（metadata/模型缺失 → 全部 icon=""）。 */
internal class OnnxIconClassifierTest {
    @Test
    fun `ready is false and classify empty when model asset missing`() {
        val classifier = OnnxIconClassifier(VisionModelStore { null }, IconLabelTable.parse("{}"))
        assertThat(classifier.ready).isFalse()
        val candidate = IconCandidate(Rect(0, 0, 1, 1))
        val out = classifier.classify(VisionImage(1, 1, intArrayOf(0)), listOf(candidate))
        assertThat(out).isEmpty()
    }
}
