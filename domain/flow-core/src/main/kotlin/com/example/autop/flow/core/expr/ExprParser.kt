package com.example.autop.flow.core.expr

import com.example.autop.flow.FlowErrorCodes

/** 流程执行中的受控错误：携带稳定错误码，由解释器转成节点失败。 */
public class FlowException(
    public val code: String,
    message: String,
) : RuntimeException(message)

/** 表达式 AST。 */
public sealed interface Expr {
    public data class Lit(
        val value: Any?,
    ) : Expr

    public data class Ref(
        val name: String,
    ) : Expr

    public data class Field(
        val target: Expr,
        val name: String,
    ) : Expr

    public data class Index(
        val target: Expr,
        val index: Expr,
    ) : Expr

    public data class Unary(
        val op: String,
        val operand: Expr,
    ) : Expr

    public data class Binary(
        val op: String,
        val left: Expr,
        val right: Expr,
    ) : Expr

    public data class Call(
        val name: String,
        val args: List<Expr>,
    ) : Expr

    public data class ListLit(
        val items: List<Expr>,
    ) : Expr

    public data class ObjectLit(
        val entries: List<Pair<String, Expr>>,
    ) : Expr
}

/** 表达式解析器（递归下降）。语法见 docs/design/flows.md §3.2。 */
public class ExprParser private constructor(
    private val src: String,
) {
    private var pos = 0

    public companion object {
        private val BINARY_LEVELS: List<List<String>> =
            listOf(
                listOf("||"),
                listOf("&&"),
                listOf("==", "!="),
                listOf("<=", ">=", "<", ">"),
                listOf("+", "-"),
                listOf("*", "/", "%"),
            )

        /** 解析表达式；语法错误抛 [FlowException]（`flow.expr_error`）。 */
        public fun parse(source: String): Expr {
            val parser = ExprParser(source)
            val expr = parser.parseBinary(0)
            parser.skipSpaces()
            if (parser.pos < source.length) parser.fail("多余的内容")
            return expr
        }
    }

    private fun fail(reason: String): Nothing =
        throw FlowException(FlowErrorCodes.EXPR_ERROR, "表达式错误（位置 $pos）：$reason：$src")

    private fun skipSpaces() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peekOp(ops: List<String>): String? {
        skipSpaces()
        return ops.firstOrNull { src.startsWith(it, pos) }
    }

    private fun parseBinary(level: Int): Expr {
        if (level >= BINARY_LEVELS.size) return parseUnary()
        var left = parseBinary(level + 1)
        while (true) {
            val op = peekOp(BINARY_LEVELS[level]) ?: return left
            // 单字符 "<" ">" 不能吞掉 "<=" ">="（已按长度优先排序），"=" 前缀由 "==" 自身匹配。
            pos += op.length
            left = Expr.Binary(op, left, parseBinary(level + 1))
        }
    }

    private fun parseUnary(): Expr {
        skipSpaces()
        if (pos < src.length) {
            val c = src[pos]
            if (c == '!' && !src.startsWith("!=", pos)) {
                pos++
                return Expr.Unary("!", parseUnary())
            }
            if (c == '-') {
                pos++
                return Expr.Unary("-", parseUnary())
            }
        }
        return parsePostfix(parsePrimary())
    }

    private fun parsePostfix(base: Expr): Expr {
        var expr = base
        while (true) {
            skipSpaces()
            if (pos >= src.length) return expr
            when (src[pos]) {
                '.' -> {
                    pos++
                    expr = Expr.Field(expr, parseIdentifier())
                }
                '[' -> {
                    pos++
                    val index = parseBinary(0)
                    expect(']')
                    expr = Expr.Index(expr, index)
                }
                else -> return expr
            }
        }
    }

    private fun expect(ch: Char) {
        skipSpaces()
        if (pos >= src.length || src[pos] != ch) fail("缺少 '$ch'")
        pos++
    }

    private fun parseIdentifier(): String {
        skipSpaces()
        val start = pos
        if (pos < src.length && (src[pos].isLetter() || src[pos] == '_')) {
            pos++
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
        }
        if (start == pos) fail("需要标识符")
        return src.substring(start, pos)
    }

    private fun parsePrimary(): Expr {
        skipSpaces()
        if (pos >= src.length) fail("表达式不完整")
        val c = src[pos]
        return when {
            c.isDigit() -> parseNumber()
            c == '"' || c == '\'' -> Expr.Lit(parseString(c))
            c == '(' -> {
                pos++
                val inner = parseBinary(0)
                expect(')')
                inner
            }
            c == '[' -> parseList()
            c == '{' -> parseObject()
            c.isLetter() || c == '_' -> parseNameOrCall()
            else -> fail("无法识别的字符 '$c'")
        }
    }

    private fun parseNumber(): Expr {
        val start = pos
        while (pos < src.length && src[pos].isDigit()) pos++
        if (pos + 1 < src.length && src[pos] == '.' && src[pos + 1].isDigit()) {
            pos++
            while (pos < src.length && src[pos].isDigit()) pos++
        }
        val text = src.substring(start, pos)
        return Expr.Lit(text.toDouble())
    }

    private fun parseString(quote: Char): String {
        pos++
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) fail("字符串未闭合")
            val ch = src[pos++]
            when {
                ch == quote -> return sb.toString()
                ch == '\\' && pos < src.length -> {
                    val esc = src[pos++]
                    sb.append(
                        when (esc) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            else -> esc
                        },
                    )
                }
                else -> sb.append(ch)
            }
        }
    }

    private fun parseList(): Expr {
        pos++
        val items = mutableListOf<Expr>()
        skipSpaces()
        if (pos < src.length && src[pos] == ']') {
            pos++
            return Expr.ListLit(items)
        }
        while (true) {
            items += parseBinary(0)
            skipSpaces()
            if (pos < src.length && src[pos] == ',') {
                pos++
            } else {
                expect(']')
                return Expr.ListLit(items)
            }
        }
    }

    private fun parseObject(): Expr {
        pos++
        val entries = mutableListOf<Pair<String, Expr>>()
        skipSpaces()
        if (pos < src.length && src[pos] == '}') {
            pos++
            return Expr.ObjectLit(entries)
        }
        while (true) {
            skipSpaces()
            val key =
                if (pos < src.length && (src[pos] == '"' || src[pos] == '\'')) {
                    parseString(src[pos])
                } else {
                    parseIdentifier()
                }
            expect(':')
            entries += key to parseBinary(0)
            skipSpaces()
            if (pos < src.length && src[pos] == ',') {
                pos++
            } else {
                expect('}')
                return Expr.ObjectLit(entries)
            }
        }
    }

    private fun parseNameOrCall(): Expr {
        val name = parseIdentifier()
        skipSpaces()
        return when (name) {
            "true" -> Expr.Lit(true)
            "false" -> Expr.Lit(false)
            "null" -> Expr.Lit(null)
            else -> if (pos < src.length && src[pos] == '(') parseCallArgs(name) else Expr.Ref(name)
        }
    }

    private fun parseCallArgs(name: String): Expr {
        pos++
        val args = mutableListOf<Expr>()
        skipSpaces()
        if (pos < src.length && src[pos] == ')') {
            pos++
            return Expr.Call(name, args)
        }
        while (true) {
            args += parseBinary(0)
            skipSpaces()
            if (pos < src.length && src[pos] == ',') {
                pos++
            } else {
                expect(')')
                return Expr.Call(name, args)
            }
        }
    }
}
