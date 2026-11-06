@file:Suppress("ThrowsCount", "MagicNumber") // 表达式求值的类型/除零错误

package com.example.autop.flow.core.expr

import com.example.autop.flow.FlowErrorCodes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId

/** 表达式求值环境：变量查找与时间/随机源（注入，便于固定）。 */
public interface ExprEnv {
    /** 返回变量值；未定义返回 null（区别于 [JsonNull]）。 */
    public fun lookup(name: String): JsonElement?

    public fun nowMs(): Long

    public fun random(
        lo: Double,
        hi: Double,
    ): Double

    public val zone: ZoneId
}

/** 表达式求值器。每次 [eval] 有步数上限，防止病态表达式。 */
public class ExprEvaluator(
    private val env: ExprEnv,
    private val maxSteps: Int,
    private val functions: Map<String, ExprFunction> = ExprFunctions.standard(),
) {
    private var steps = 0

    /** 求值字符串表达式。 */
    public fun eval(source: String): JsonElement = eval(ExprParser.parse(source))

    public fun eval(expr: Expr): JsonElement {
        steps = 0
        return walk(expr)
    }

    private fun tick() {
        steps++
        if (maxSteps > 0 && steps > maxSteps) {
            throw FlowException(FlowErrorCodes.EXPR_ERROR, "表达式求值步数超过上限 $maxSteps")
        }
    }

    private fun walk(expr: Expr): JsonElement {
        tick()
        return when (expr) {
            is Expr.Lit -> Values.of(expr.value)
            is Expr.Ref ->
                env.lookup(expr.name)
                    ?: throw FlowException(FlowErrorCodes.VAR_UNDEFINED, "变量未定义：${expr.name}")
            is Expr.Field -> field(walk(expr.target), expr.name)
            is Expr.Index -> index(walk(expr.target), walk(expr.index))
            is Expr.Unary -> unary(expr)
            is Expr.Binary -> binary(expr)
            is Expr.Call -> call(expr)
            is Expr.ListLit -> JsonArray(expr.items.map { walk(it) })
            is Expr.ObjectLit -> JsonObject(expr.entries.associate { (k, v) -> k to walk(v) })
        }
    }

    private fun field(
        target: JsonElement,
        name: String,
    ): JsonElement =
        when (target) {
            is JsonObject -> target[name] ?: JsonNull
            is JsonNull -> JsonNull
            else -> throw FlowException(FlowErrorCodes.TYPE_ERROR, "无法读取 ${Values.typeOf(target)} 的字段 $name")
        }

    private fun index(
        target: JsonElement,
        idx: JsonElement,
    ): JsonElement =
        when (target) {
            is JsonArray -> {
                val i = Values.numOrNull(idx)?.toInt()
                if (i == null) JsonNull else target.getOrNull(if (i < 0) target.size + i else i) ?: JsonNull
            }
            is JsonObject -> target[Values.str(idx)] ?: JsonNull
            is JsonNull -> JsonNull
            else -> throw FlowException(FlowErrorCodes.TYPE_ERROR, "无法对 ${Values.typeOf(target)} 取下标")
        }

    private fun unary(expr: Expr.Unary): JsonElement {
        val v = walk(expr.operand)
        return if (expr.op == "!") {
            JsonPrimitive(!Values.truthy(v))
        } else {
            Values.num(-Values.requireNum(v, "负号"))
        }
    }

    private fun binary(expr: Expr.Binary): JsonElement {
        if (expr.op == "&&") {
            val l = walk(expr.left)
            return if (!Values.truthy(l)) JsonPrimitive(false) else JsonPrimitive(Values.truthy(walk(expr.right)))
        }
        if (expr.op == "||") {
            val l = walk(expr.left)
            return if (Values.truthy(l)) JsonPrimitive(true) else JsonPrimitive(Values.truthy(walk(expr.right)))
        }
        val l = walk(expr.left)
        val r = walk(expr.right)
        return when (expr.op) {
            "==" -> JsonPrimitive(Values.equal(l, r))
            "!=" -> JsonPrimitive(!Values.equal(l, r))
            "<" -> JsonPrimitive(Values.compare(l, r) < 0)
            "<=" -> JsonPrimitive(Values.compare(l, r) <= 0)
            ">" -> JsonPrimitive(Values.compare(l, r) > 0)
            ">=" -> JsonPrimitive(Values.compare(l, r) >= 0)
            "+" -> plus(l, r)
            else -> arithmetic(expr.op, l, r)
        }
    }

    private fun plus(
        l: JsonElement,
        r: JsonElement,
    ): JsonElement {
        val ls = Values.isString(l)
        val rs = Values.isString(r)
        return when {
            ls || rs -> JsonPrimitive(Values.str(l) + Values.str(r))
            l is JsonArray && r is JsonArray -> JsonArray(l + r)
            Values.isNull(l) && Values.isNull(r) -> JsonNull
            else -> Values.num(Values.requireNum(l, "加法") + Values.requireNum(r, "加法"))
        }
    }

    private fun arithmetic(
        op: String,
        l: JsonElement,
        r: JsonElement,
    ): JsonElement {
        val a = Values.requireNum(l, "算术运算")
        val b = Values.requireNum(r, "算术运算")
        return when (op) {
            "-" -> Values.num(a - b)
            "*" -> Values.num(a * b)
            "/" -> {
                if (b == 0.0) throw FlowException(FlowErrorCodes.EXPR_ERROR, "除数为 0")
                Values.num(a / b)
            }
            "%" -> {
                if (b == 0.0) throw FlowException(FlowErrorCodes.EXPR_ERROR, "除数为 0")
                Values.num(a % b)
            }
            else -> throw FlowException(FlowErrorCodes.EXPR_ERROR, "未知运算符 $op")
        }
    }

    private fun call(expr: Expr.Call): JsonElement {
        val fn =
            functions[expr.name]
                ?: throw FlowException(FlowErrorCodes.EXPR_ERROR, "未知函数：${expr.name}")
        // if/coalesce 需要惰性求值，其余先求值参数。
        if (expr.name == "if" && expr.args.size == 3) {
            return if (Values.truthy(walk(expr.args[0]))) walk(expr.args[1]) else walk(expr.args[2])
        }
        val args = expr.args.map { walk(it) }
        return fn.invoke(env, args)
    }
}
