@file:Suppress("SwallowedException", "MagicNumber") // 函数库：解析失败按语义返回 null

package com.example.autop.flow.core.expr

import com.example.autop.flow.FlowErrorCodes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 表达式函数：纯函数，依赖只来自 [ExprEnv]。 */
public fun interface ExprFunction {
    public fun invoke(
        env: ExprEnv,
        args: List<JsonElement>,
    ): JsonElement
}

/** 标准函数库（docs/design/flows.md §3.3）。 */
public object ExprFunctions {
    private const val UNTRUSTED_OPEN = "<untrusted source=\"flow\">"
    private const val UNTRUSTED_CLOSE = "</untrusted>"
    private const val ARG_LATEST = 0

    private val lenient = Json { isLenient = true }

    /** 构造标准函数表。 */
    public fun standard(): Map<String, ExprFunction> =
        buildMap {
            text(this)
            numbers(this)
            lists(this)
            objects(this)
            json(this)
            time(this)
            logic(this)
        }

    private fun arg(
        name: String,
        args: List<JsonElement>,
        i: Int,
    ): JsonElement = args.getOrNull(i) ?: throw FlowException(FlowErrorCodes.EXPR_ERROR, "函数 $name 缺少第 ${i + 1} 个参数")

    private fun optArg(
        args: List<JsonElement>,
        i: Int,
    ): JsonElement? = args.getOrNull(i)?.takeUnless { it is JsonNull }

    private fun MutableMap<String, ExprFunction>.fn(
        name: String,
        body: (ExprEnv, List<JsonElement>) -> JsonElement,
    ) {
        put(name) { env, args -> body(env, args) }
    }

    private fun str1(
        name: String,
        args: List<JsonElement>,
    ): String = Values.str(arg(name, args, 0))

    private fun text(m: MutableMap<String, ExprFunction>) {
        m.fn("len") { _, a ->
            when (val v = arg("len", a, 0)) {
                is JsonArray -> JsonPrimitive(v.size)
                is JsonObject -> JsonPrimitive(v.size)
                else -> JsonPrimitive(Values.str(v).length)
            }
        }
        m.fn("upper") { _, a -> JsonPrimitive(str1("upper", a).uppercase()) }
        m.fn("lower") { _, a -> JsonPrimitive(str1("lower", a).lowercase()) }
        m.fn("trim") { _, a -> JsonPrimitive(str1("trim", a).trim()) }
        m.fn("str") { _, a -> JsonPrimitive(str1("str", a)) }
        m.fn(
            "startsWith",
        ) { _, a -> JsonPrimitive(str1("startsWith", a).startsWith(Values.str(arg("startsWith", a, 1)))) }
        m.fn("endsWith") { _, a -> JsonPrimitive(str1("endsWith", a).endsWith(Values.str(arg("endsWith", a, 1)))) }
        m.fn("replace") { _, a ->
            JsonPrimitive(
                str1("replace", a).replace(Values.str(arg("replace", a, 1)), Values.str(arg("replace", a, 2))),
            )
        }
        m.fn("split") { _, a ->
            val sep = Values.str(arg("split", a, 1))
            JsonArray(str1("split", a).split(sep).map { JsonPrimitive(it) })
        }
        m.fn("join") { _, a ->
            val sep = Values.str(arg("join", a, 1))
            JsonPrimitive(Values.requireList(arg("join", a, 0), "join").joinToString(sep) { Values.str(it) })
        }
        m.fn("substr") { _, a ->
            val s = str1("substr", a)
            val start = Values.requireNum(arg("substr", a, 1), "substr").toInt().coerceIn(0, s.length)
            val len = optArg(a, 2)?.let { Values.requireNum(it, "substr").toInt() } ?: (s.length - start)
            JsonPrimitive(s.substring(start, (start + len).coerceIn(start, s.length)))
        }
        m.fn(
            "regexMatch",
        ) { _, a -> JsonPrimitive(regex(arg("regexMatch", a, 1)).containsMatchIn(str1("regexMatch", a))) }
        m.fn("regexExtract") { _, a ->
            val group = optArg(a, 2)?.let { Values.requireNum(it, "regexExtract").toInt() } ?: 0
            val match = regex(arg("regexExtract", a, 1)).find(str1("regexExtract", a))
            match
                ?.groups
                ?.get(group)
                ?.value
                ?.let { JsonPrimitive(it) } ?: JsonNull
        }
        m.fn("regexReplace") { _, a ->
            JsonPrimitive(
                regex(
                    arg("regexReplace", a, 1),
                ).replace(str1("regexReplace", a), Values.str(arg("regexReplace", a, 2))),
            )
        }
        m.fn("untrusted") { _, a -> JsonPrimitive(untrusted(str1("untrusted", a))) }
    }

    /**
     * 把来自屏幕、通知、网络的不可信文本包上信封，避免其中的文字被当成指令。
     * 与表达式函数 `untrusted` 产出同一格式。
     */
    public fun untrusted(text: String): String = "$UNTRUSTED_OPEN${text.replace(UNTRUSTED_CLOSE, "")}$UNTRUSTED_CLOSE"

    private fun regex(pattern: JsonElement): Regex =
        try {
            Regex(Values.str(pattern))
        } catch (e: IllegalArgumentException) {
            throw FlowException(FlowErrorCodes.EXPR_ERROR, "正则无效：${e.message}")
        }

    private fun numbers(m: MutableMap<String, ExprFunction>) {
        m.fn("num") { _, a -> Values.numOrNull(arg("num", a, 0))?.let { Values.num(it) } ?: JsonNull }
        m.fn("round") { _, a ->
            val x = Values.requireNum(arg("round", a, 0), "round")
            val digits = optArg(a, 1)?.let { Values.requireNum(it, "round").toInt() } ?: 0
            val scale = Math.pow(10.0, digits.toDouble())
            Values.num(Math.round(x * scale) / scale)
        }
        m.fn("floor") { _, a -> Values.num(Math.floor(Values.requireNum(arg("floor", a, 0), "floor"))) }
        m.fn("ceil") { _, a -> Values.num(Math.ceil(Values.requireNum(arg("ceil", a, 0), "ceil"))) }
        m.fn("abs") { _, a -> Values.num(Math.abs(Values.requireNum(arg("abs", a, 0), "abs"))) }
        m.fn(
            "min",
        ) { _, a ->
            Values.num(
                minOf(Values.requireNum(arg("min", a, 0), "min"), Values.requireNum(arg("min", a, 1), "min")),
            )
        }
        m.fn(
            "max",
        ) { _, a ->
            Values.num(
                maxOf(Values.requireNum(arg("max", a, 0), "max"), Values.requireNum(arg("max", a, 1), "max")),
            )
        }
        m.fn("clamp") { _, a ->
            val x = Values.requireNum(arg("clamp", a, 0), "clamp")
            val lo = Values.requireNum(arg("clamp", a, 1), "clamp")
            val hi = Values.requireNum(arg("clamp", a, 2), "clamp")
            Values.num(x.coerceIn(minOf(lo, hi), maxOf(lo, hi)))
        }
        m.fn("random") { env, a ->
            val lo = Values.requireNum(arg("random", a, 0), "random")
            val hi = Values.requireNum(arg("random", a, 1), "random")
            Values.num(Math.floor(env.random(minOf(lo, hi), maxOf(lo, hi))))
        }
    }

    private fun listOf1(
        name: String,
        a: List<JsonElement>,
    ): List<JsonElement> = Values.requireList(arg(name, a, 0), name)

    private fun lists(m: MutableMap<String, ExprFunction>) {
        m.fn("first") { _, a -> listOf1("first", a).firstOrNull() ?: JsonNull }
        m.fn("last") { _, a -> listOf1("last", a).lastOrNull() ?: JsonNull }
        m.fn("slice") { _, a ->
            val l = listOf1("slice", a)
            val from = Values.requireNum(arg("slice", a, 1), "slice").toInt().coerceIn(0, l.size)
            val to = optArg(a, 2)?.let { Values.requireNum(it, "slice").toInt() }?.coerceIn(from, l.size) ?: l.size
            JsonArray(l.subList(from, to))
        }
        m.fn(
            "head",
        ) { _, a -> JsonArray(listOf1("head", a).take(Values.requireNum(arg("head", a, 1), "head").toInt())) }
        m.fn(
            "tail",
        ) { _, a -> JsonArray(listOf1("tail", a).takeLast(Values.requireNum(arg("tail", a, 1), "tail").toInt())) }
        m.fn("append") { _, a -> JsonArray(listOf1("append", a) + arg("append", a, 1)) }
        m.fn("prepend") { _, a -> JsonArray(listOf(arg("prepend", a, 1)) + listOf1("prepend", a)) }
        m.fn("concat") { _, a -> JsonArray(listOf1("concat", a) + Values.requireList(arg("concat", a, 1), "concat")) }
        m.fn("reverse") { _, a -> JsonArray(listOf1("reverse", a).reversed()) }
        m.fn("unique") { _, a -> JsonArray(listOf1("unique", a).distinct()) }
        m.fn("pluck") { _, a ->
            val key = Values.str(arg("pluck", a, 1))
            JsonArray(listOf1("pluck", a).map { (it as? JsonObject)?.get(key) ?: JsonNull })
        }
        m.fn("range") { _, a ->
            val n = Values.requireNum(arg("range", a, 0), "range").toInt().coerceAtLeast(0)
            JsonArray((0 until n).map { JsonPrimitive(it) })
        }
        m.fn("get") { _, a -> getValue(a) }
        m.fn("contains") { _, a ->
            val c = arg("contains", a, 0)
            val x = arg("contains", a, 1)
            JsonPrimitive(
                when (c) {
                    is JsonArray -> c.any { Values.equal(it, x) }
                    else -> Values.str(c).contains(Values.str(x))
                },
            )
        }
        m.fn("indexOf") { _, a ->
            val c = arg("indexOf", a, 0)
            val x = arg("indexOf", a, 1)
            JsonPrimitive(
                when (c) {
                    is JsonArray -> c.indexOfFirst { Values.equal(it, x) }
                    else -> Values.str(c).indexOf(Values.str(x))
                },
            )
        }
    }

    private fun getValue(a: List<JsonElement>): JsonElement {
        val target = arg("get", a, 0)
        val key = arg("get", a, 1)
        val default = optArg(a, 2) ?: JsonNull
        val found =
            when (target) {
                is JsonArray ->
                    Values.numOrNull(key)?.toInt()?.let {
                        target.getOrNull(
                            if (it <
                                0
                            ) {
                                target.size + it
                            } else {
                                it
                            },
                        )
                    }
                is JsonObject -> path(target, Values.str(key))
                else -> null
            }
        return found?.takeUnless { it is JsonNull } ?: default
    }

    private fun path(
        root: JsonObject,
        path: String,
    ): JsonElement? {
        var cur: JsonElement = root
        for (part in path.split('.')) {
            cur =
                when (cur) {
                    is JsonObject -> cur[part] ?: return null
                    is JsonArray -> part.toIntOrNull()?.let { cur.asArray().getOrNull(it) } ?: return null
                    else -> return null
                }
        }
        return cur
    }

    private fun JsonElement.asArray(): JsonArray = this as JsonArray

    private fun objects(m: MutableMap<String, ExprFunction>) {
        m.fn(
            "has",
        ) { _, a ->
            JsonPrimitive(
                Values.requireObject(arg("has", a, 0), "has").containsKey(Values.str(arg("has", a, 1))),
            )
        }
        m.fn(
            "keys",
        ) { _, a -> JsonArray(Values.requireObject(arg("keys", a, 0), "keys").keys.map { JsonPrimitive(it) }) }
        m.fn("values") { _, a -> JsonArray(Values.requireObject(arg("values", a, 0), "values").values.toList()) }
        m.fn("merge") { _, a ->
            JsonObject(
                Values.requireObject(arg("merge", a, 0), "merge") + Values.requireObject(arg("merge", a, 1), "merge"),
            )
        }
        m.fn("set") { _, a ->
            val obj = Values.requireObject(arg("set", a, 0), "set")
            setPath(obj, Values.str(arg("set", a, 1)).split('.'), arg("set", a, 2))
        }
    }

    private fun setPath(
        obj: JsonObject,
        parts: List<String>,
        value: JsonElement,
    ): JsonObject {
        val head = parts.first()
        if (parts.size == 1) return JsonObject(obj + (head to value))
        val child = (obj[head] as? JsonObject) ?: JsonObject(emptyMap())
        return JsonObject(obj + (head to setPath(child, parts.drop(1), value)))
    }

    private fun json(m: MutableMap<String, ExprFunction>) {
        m.fn("json") { _, a ->
            try {
                lenient.parseToJsonElement(str1("json", a))
            } catch (_: IllegalArgumentException) {
                JsonNull
            }
        }
        m.fn("toJson") { _, a -> JsonPrimitive(arg("toJson", a, 0).toString()) }
    }

    private fun zoned(
        env: ExprEnv,
        ms: JsonElement?,
    ): ZonedDateTime {
        val t = ms?.let { Values.requireNum(it, "时间").toLong() } ?: env.nowMs()
        return Instant.ofEpochMilli(t).atZone(env.zone)
    }

    private fun time(m: MutableMap<String, ExprFunction>) {
        m.fn("now") { env, _ -> JsonPrimitive(env.nowMs()) }
        m.fn("formatTime") { env, a ->
            val fmt = DateTimeFormatter.ofPattern(Values.str(arg("formatTime", a, 1)))
            JsonPrimitive(zoned(env, arg("formatTime", a, 0)).format(fmt))
        }
        m.fn("hour") { env, a -> JsonPrimitive(zoned(env, optArg(a, ARG_LATEST)).hour) }
        m.fn("minute") { env, a -> JsonPrimitive(zoned(env, optArg(a, ARG_LATEST)).minute) }
        m.fn("weekday") { env, a -> JsonPrimitive(zoned(env, optArg(a, ARG_LATEST)).dayOfWeek.value) }
        m.fn("dayKey") { env, a ->
            JsonPrimitive(zoned(env, optArg(a, ARG_LATEST)).format(DateTimeFormatter.ISO_LOCAL_DATE))
        }
        m.fn("addMs") { _, a ->
            Values.num(Values.requireNum(arg("addMs", a, 0), "addMs") + Values.requireNum(arg("addMs", a, 1), "addMs"))
        }
    }

    private fun logic(m: MutableMap<String, ExprFunction>) {
        // if 在求值器中惰性处理；这里给出严格版本，供作为值传递时使用。
        m.fn("if") { _, a -> if (Values.truthy(arg("if", a, 0))) arg("if", a, 1) else arg("if", a, 2) }
        m.fn("coalesce") { _, a -> a.firstOrNull { !Values.isNull(it) } ?: JsonNull }
        m.fn("isNull") { _, a -> JsonPrimitive(Values.isNull(arg("isNull", a, 0))) }
        m.fn("isEmpty") { _, a ->
            JsonPrimitive(
                when (val v = arg("isEmpty", a, 0)) {
                    is JsonNull -> true
                    is JsonArray -> v.isEmpty()
                    is JsonObject -> v.isEmpty()
                    else -> Values.str(v).isEmpty()
                },
            )
        }
        m.fn("typeOf") { _, a -> JsonPrimitive(Values.typeOf(arg("typeOf", a, 0))) }
    }
}
