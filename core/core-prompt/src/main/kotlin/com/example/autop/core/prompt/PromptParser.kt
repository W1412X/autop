package com.example.autop.core.prompt

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome

/**
 * 解析模板文件（docs/tech/02 §3.1）：`---` 包围的 YAML 子集头部 + 正文。
 *
 * 头部只支持 `key: value`、`key: [a, b]` 与一层 `meta:` 映射（两空格缩进 `k: v`）；
 * `vars` 是全部变量声明，`optionalVars` 是其中可缺省的子集。
 * 正文出现未声明变量 → [PromptLoadException]（`prompt.undeclared_var`）；
 * `{{#if}}` 不配对、嵌套或语法非法 → [IllegalStateException]（模板是代码，属编程错误）。
 */
public fun parsePromptTemplate(
    text: String,
    source: PromptSource,
): PromptTemplate {
    val lines = text.lines().map { it.trimEnd(CARRIAGE_RETURN) }
    check(lines.firstOrNull() == HEADER_DELIMITER) { "template must start with '$HEADER_DELIMITER'" }
    val end = lines.drop(1).indexOf(HEADER_DELIMITER)
    check(end >= 0) { "template header is not closed by '$HEADER_DELIMITER'" }
    val header = parseHeader(lines.subList(1, end + 1))
    val body = parseBody(lines.drop(end + 2))
    val id = header[ID_KEY] ?: error("template header is missing 'id'")
    val version = header[VERSION_KEY]?.toIntOrNull() ?: error("template header is missing numeric 'version'")
    val vars = parseListValue(header[VARS_KEY], VARS_KEY)
    val optionalVars = parseListValue(header[OPTIONAL_VARS_KEY], OPTIONAL_VARS_KEY)
    check(vars.containsAll(optionalVars)) { "optionalVars must be a subset of vars in '$id'" }
    validateBody(id, body, vars, optionalVars)
    return PromptTemplate(
        id = id,
        version = version,
        vars = vars,
        optionalVars = optionalVars,
        meta = parseMeta(lines.subList(1, end + 1)),
        body = body,
        source = source,
    )
}

/**
 * 校验一段正文能否作为 [template] 的用户覆盖正文：变量与 `{{#if}}` 结构必须合法。
 *
 * 未声明变量 → `prompt.undeclared_var`；结构非法是编程错误，照常抛 [IllegalStateException]。
 */
public fun validatePromptBody(
    body: String,
    template: PromptTemplate,
): Outcome<Unit> =
    try {
        validateBody(template.id, body, template.vars, template.optionalVars)
        Outcome.Ok(Unit)
    } catch (e: PromptLoadException) {
        Outcome.Err(e.error)
    }

private fun parseHeader(lines: List<String>): Map<String, String> {
    val header = LinkedHashMap<String, String>()
    var inMeta = false
    for (line in lines) {
        when {
            line == META_KEY -> inMeta = true
            line.startsWith(META_INDENT) -> check(inMeta) { "meta entry outside 'meta:' block: '$line'" }
            line.isBlank() -> inMeta = false
            else -> {
                inMeta = false
                val (key, value) = splitEntry(line)
                check(key in HEADER_KEYS) { "unknown template header key '$key'" }
                check(key !in header) { "duplicate template header key '$key'" }
                header[key] = value
            }
        }
    }
    return header
}

private fun parseMeta(lines: List<String>): Map<String, String> {
    val meta = LinkedHashMap<String, String>()
    var inMeta = false
    for (line in lines) {
        when {
            line == META_KEY -> inMeta = true
            line.startsWith(META_INDENT) && inMeta -> {
                val (key, value) = splitEntry(line.trim())
                check(key !in meta) { "duplicate template meta key '$key'" }
                meta[key] = value
            }
            line.isNotBlank() -> inMeta = false
        }
    }
    return meta
}

private fun splitEntry(line: String): Pair<String, String> {
    val index = line.indexOf(": ")
    check(index > 0) { "template header entry must be 'key: value', got '$line'" }
    return line.substring(0, index) to line.substring(index + 2)
}

private fun parseListValue(
    raw: String?,
    key: String,
): Set<String> {
    if (raw == null) return emptySet()
    val value = raw.trim()
    check(value.length >= 2 && value.first() == '[' && value.last() == ']') { "'$key' must be a list like [a, b]" }
    return value
        .substring(1, value.lastIndex)
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
}

private fun parseBody(lines: List<String>): String {
    val content = lines.dropWhile { it.isBlank() }
    return content.joinToString("\n").trimEnd()
}

private fun validateBody(
    id: String,
    body: String,
    vars: Set<String>,
    optionalVars: Set<String>,
) {
    val declared = vars + optionalVars
    var depth = 0
    for (match in TEMPLATE_TOKEN.findAll(body)) {
        val token = match.groupValues[1].trim()
        when {
            token == IF_END -> {
                depth--
                check(depth >= 0) { "unmatched '{{/if}}' in template '$id'" }
            }
            token.startsWith(IF_START) -> {
                check(depth == 0) { "nested '{{#if}}' is not supported in template '$id'" }
                val name = token.removePrefix(IF_START)
                check(VARIABLE.matches(name)) { "invalid '{{#if}}' variable in template '$id'" }
                requireDeclared(name, declared, id)
                depth++
            }
            VARIABLE.matches(token) -> requireDeclared(token, declared, id)
            else -> error("invalid template token '{{$token}}' in template '$id'")
        }
    }
    check(depth == 0) { "unclosed '{{#if}}' in template '$id'" }
}

private fun requireDeclared(
    name: String,
    declared: Set<String>,
    id: String,
) {
    if (name !in declared) {
        throw PromptLoadException(
            AppError(
                code = PromptErrorCodes.UNDECLARED_VAR,
                message = "undeclared variable '$name' in template '$id'",
            ),
        )
    }
}

private const val HEADER_DELIMITER = "---"
private const val ID_KEY = "id"
private const val VERSION_KEY = "version"
private const val VARS_KEY = "vars"
private const val OPTIONAL_VARS_KEY = "optionalVars"
private const val META_KEY = "meta:"
private const val META_INDENT = "  "
private const val IF_START = "#if "
private const val IF_END = "/if"
private const val CARRIAGE_RETURN = '\r'
private val HEADER_KEYS = setOf(ID_KEY, VERSION_KEY, VARS_KEY, OPTIONAL_VARS_KEY)

// 全转义风格（N134）：Android 的 java.util.regex 底层是 ICU，对裸 `}` 等比 OpenJDK 严格，
// 未转义会在真机 <clinit> 抛 PatternSyntaxException（JVM 单测无法暴露，androidTest 冒烟覆盖）。
internal val TEMPLATE_TOKEN = Regex("\\{\\{([^\\{\\}]*)\\}\\}")
private val VARIABLE = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
