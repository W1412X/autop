/**
 * JSON 树视图（N210）：工具测试输出与参数按树级展开查看。
 * 容器节点可逐层展开折叠；非 JSON 文本原样退回等宽纯文本展示。
 * 树整体可横向滑动：层级很深时缩进撑宽内容，不再被屏幕宽度截断。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.autop.core.common.JsonTreeNode
import com.example.autop.core.common.JsonTreeParser

/** 容器节点默认展开深度（0＝全折叠，1＝根展开）。 */
private const val DEFAULT_EXPAND_DEPTH: Int = 1

/** 每层相对父层的缩进宽度（子节点容器只缩进一次；父层的缩进已包含在外层容器里，不能再按深度累乘）。 */
private const val INDENT_DP: Int = 12

/**
 * 叶子值的最大宽度：横向滚动容器给子项的是无限宽度，超长字符串若不设上限会变成一行超宽文本，
 * 超过 Compose 单次测量的宽度上限；到达该宽度后换行。
 */
private const val LEAF_VALUE_MAX_WIDTH_DP: Int = 480

/** 展开标记。 */
private const val EXPANDED_MARK: String = "▾"

/** 折叠标记。 */
private const val COLLAPSED_MARK: String = "▸"

/**
 * JSON 树视图（N210）：把 JSON 文本按层级渲染成可展开的树，便于逐键查看。
 *
 * @param text 待展示文本（JSON 或普通文本）。
 * @param modifier 修饰符。
 */
@Composable
public fun JsonTreeView(
    text: String,
    modifier: Modifier = Modifier,
) {
    val tree = remember(text) { JsonTreeParser.parse(text) }
    val root = tree.root
    if (root == null) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = modifier,
        )
        return
    }
    val expanded = remember(text) { mutableStateMapOf<String, Boolean>() }
    Column(modifier = modifier) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            JsonNodeView(node = root, path = "", depth = 0, expanded = expanded)
        }
        if (tree.trailing.isNotBlank()) {
            Text(
                text = tree.trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 树节点渲染：叶子单行，容器可展开。 */
@Composable
private fun JsonNodeView(
    node: JsonTreeNode,
    path: String,
    depth: Int,
    expanded: MutableMap<String, Boolean>,
) {
    when (node) {
        is JsonTreeNode.Leaf -> LeafView(node = node)
        is JsonTreeNode.Branch ->
            BranchView(node = node, path = path, depth = depth, expanded = expanded)
    }
}

/** 叶子节点：键名与值同行。 */
@Composable
private fun LeafView(node: JsonTreeNode.Leaf) {
    Row {
        if (node.key.isNotEmpty()) {
            Text(
                text = node.key + ":",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = node.value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.widthIn(max = LEAF_VALUE_MAX_WIDTH_DP.dp),
        )
    }
}

/** 容器节点：标题可点选展开/折叠，展开后递归渲染子节点。 */
@Composable
private fun BranchView(
    node: JsonTreeNode.Branch,
    path: String,
    depth: Int,
    expanded: MutableMap<String, Boolean>,
) {
    val isOpen = expanded[path] ?: (depth < DEFAULT_EXPAND_DEPTH)
    Column {
        Row(
            modifier = Modifier.clickable { expanded[path] = !isOpen },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isOpen) EXPANDED_MARK else COLLAPSED_MARK,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = headerOf(node),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (isOpen) {
            Column(modifier = Modifier.padding(start = INDENT_DP.dp)) {
                node.children.forEachIndexed { index, child ->
                    JsonNodeView(node = child, path = "$path.$index", depth = depth + 1, expanded = expanded)
                }
            }
        }
    }
}

/** 容器标题：键名 + 类型 + 子项数。 */
private fun headerOf(node: JsonTreeNode.Branch): String {
    val isObject = node.kind == JsonTreeNode.Branch.Kind.OBJECT
    val open = if (isObject) "{" else "["
    val close = if (isObject) "}" else "]"
    val key = if (node.key.isEmpty()) "" else node.key + " "
    return key + open + node.children.size + close
}
