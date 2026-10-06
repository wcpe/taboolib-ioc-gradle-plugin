package top.wcpe.taboolib.ioc.gradle.weaving

import java.nio.file.Files
import java.nio.file.Path

/** 织入产物标记接口（`internal name`，斜杠形式）。必须与 `AopWeaver` 生成的 `implements` 逐字一致。 */
internal const val WEAVE_TARGET_INTERNAL: String = "top/wcpe/taboolib/ioc/aop/WovenTarget"

/** 织入产物标记接口（点号 FQCN，供类索引 / 诊断过滤使用）。 */
internal const val WEAVE_TARGET_DOTTED: String = "top.wcpe.taboolib.ioc.aop.WovenTarget"

/** DI 织入产物标记接口（点号 FQCN；阶段 ③ 使用，诊断必须一并剔除）。 */
internal const val INJECTION_TARGET_DOTTED: String = "top.wcpe.taboolib.ioc.inject.InjectionWovenTarget"

/**
 * 诊断侧必须从「已实现接口」中剔除的织入产物标记接口集合。
 *
 * 这些接口是织入器自己加上去的，不属于业务类型。若不过滤，任何「按接口判定」的规则
 * （首当其冲 `aop-target-not-proxied`）都会随「字节码是否已被就地织入」而漂移 → 不可复现。
 * 见 §2.3.7 / §7「诊断残渣免疫契约」。
 */
internal val WEAVING_MARKER_INTERFACES: Set<String> = setOf(WEAVE_TARGET_DOTTED, INJECTION_TARGET_DOTTED)

/** 残渣条目在无法复原命中通知（切面集合不可用）时使用的哨兵键，绝不与真实 `<切面FQCN>#<通知>` 冲突。 */
internal const val ALREADY_WOVEN_SENTINEL: String = "<already-woven>"

/** 织入计划落盘位置（相对 `buildDirectory`）。生产者：`planTaboolibIocAop`；消费者：`analyzeTaboolibIocBeans`。 */
internal const val WEAVE_PLAN_RELATIVE_PATH: String = "taboolib-ioc/aop-weave-plan.json"

internal enum class WeaveOutcome {
    WOVEN,
    SKIPPED,
}

/**
 * 真实跳过原因。
 *
 * **不得包含 `ALREADY_WOVEN`**：既已织入的类是「确已织入」，语义为 [WeaveOutcome.WOVEN] +
 * `alreadyWoven=true`（正交审计位），把它记成 `SKIPPED` 会让诊断把命中通知判为未实现 → 假阳性。
 */
internal enum class WeaveSkipReason {
    INTERFACE,
    NO_ASPECT_CLASS,
    MODULE_INFO,
    NO_ELIGIBLE_MATCHING_METHOD,

    /**
     * 本类就是切面类本身，不参与织入。
     *
     * `execution(*.*)` 这类通配切点解析出的 classPattern 是 `*`，切面自己的通知方法同样满足
     * 资格判据。织入后运行期反射调用通知会落在转发体上、再次进入 invoke 并按当前注册表匹配到
     * 同一个通知 —— 通知体被多套一层，实现里再反射调用通知方法就是自递归（StackOverflowError）。
     * 诊断侧本就显式跳过切面 Bean（「切面 Bean 自身不被代理」），两侧口径必须一致。
     */
    ASPECT_CLASS,
}

/** 被实际织入（或残渣中已织入）的单个方法：方法名 + 描述符 + 命中的通知键列表。 */
internal data class WovenMethod(
    val methodName: String,
    val descriptor: String,
    val matchedAdvices: List<String>,
)

/**
 * 单个类的织入决策（`AopWeaver.plan` 的唯一产物）。
 *
 * @param className 点号 FQCN
 * @param outcome [WeaveOutcome.WOVEN] 表示「该类确实被织入（本次或上一轮残渣）」
 * @param alreadyWoven 残渣审计位：true 表示该字节码在本次运行前**已被织入**（上一次构建就地写入）
 * @param skipReason 仅当 `outcome == SKIPPED` 时非空
 * @param wovenMethods `outcome == WOVEN` 时非空（残渣时由 `*$ioc$original` 合成方法重建）
 */
internal data class ClassWeaveDecision(
    val className: String,
    val outcome: WeaveOutcome,
    val alreadyWoven: Boolean = false,
    val skipReason: WeaveSkipReason? = null,
    val wovenMethods: List<WovenMethod> = emptyList(),
)

/**
 * 织入计划：引擎自报的「会织入什么」的事实，供诊断侧据此抑制 `aop-target-not-proxied`。
 *
 * 这是「单一真源」的**正确层级**（整类织入决策层）：方法级谓词只覆盖 access 维度，
 * 覆盖不到 match 级语义（声明位置、`@NoAspect`、继承/通配）。诊断侧**禁止**再引入任何
 * 「预测引擎是否织入」的判据（见 §2.3.6 / §7）。
 */
internal data class WeavePlan(
    val adviceCount: Int,
    val classes: List<ClassWeaveDecision>,
) {
    /** `outcome == WOVEN` 的类数（含残渣）。 */
    val wovenClassCount: Int get() = classes.count { it.outcome == WeaveOutcome.WOVEN }

    companion object {
        /** 计划 schema 版本；不匹配视为「无计划」→ 保守不抑制。 */
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * 织入计划的落盘 / 读取（手写 JSON，**零新依赖**，风格对齐 `StaticAnalysisJsonWriter`）。
 *
 * 契约（§7）：
 * - 缺失 / 解析失败 / `schemaVersion` 不匹配 → [read] 返回 `null` = 「无计划」→ 诊断**保守不抑制**；
 * - 计划键格式 `<切面FQCN>#<通知方法名>`；
 * - 落盘 `build/taboolib-ioc/aop-weave-plan.json`。
 */
internal object WeavePlanJson {

    fun write(plan: WeavePlan, outputFile: Path) {
        outputFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(outputFile, serialize(plan.toJsonValue()))
    }

    fun read(inputFile: Path): WeavePlan? {
        if (!Files.isRegularFile(inputFile)) return null
        val text = try {
            Files.readString(inputFile)
        } catch (_: Throwable) {
            return null
        }
        return try {
            fromJsonValue(JsonParser(text).parseRoot())
        } catch (_: Throwable) {
            null
        }
    }

    // ── 序列化 ──

    private fun WeavePlan.toJsonValue(): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to WeavePlan.SCHEMA_VERSION,
        "adviceCount" to adviceCount,
        "wovenClassCount" to wovenClassCount,
        "classes" to classes.map { decision ->
            linkedMapOf(
                "className" to decision.className,
                "outcome" to decision.outcome.name,
                "alreadyWoven" to decision.alreadyWoven,
                "skipReason" to decision.skipReason?.name,
                "wovenMethods" to decision.wovenMethods.map { woven ->
                    linkedMapOf(
                        "methodName" to woven.methodName,
                        "descriptor" to woven.descriptor,
                        "matchedAdvices" to woven.matchedAdvices,
                    )
                },
            )
        },
    )

    // ── 反序列化 ──

    private fun fromJsonValue(root: Any?): WeavePlan? {
        val map = root as? Map<*, *> ?: return null
        val schema = (map["schemaVersion"] as? Number)?.toInt() ?: return null
        if (schema != WeavePlan.SCHEMA_VERSION) return null
        val adviceCount = (map["adviceCount"] as? Number)?.toInt() ?: 0
        val classesRaw = map["classes"] as? List<*> ?: return null
        val classes = classesRaw.mapNotNull { toDecision(it) }
        return WeavePlan(adviceCount = adviceCount, classes = classes)
    }

    private fun toDecision(value: Any?): ClassWeaveDecision? {
        val map = value as? Map<*, *> ?: return null
        val className = map["className"] as? String ?: return null
        val outcome = when (map["outcome"] as? String) {
            WeaveOutcome.WOVEN.name -> WeaveOutcome.WOVEN
            WeaveOutcome.SKIPPED.name -> WeaveOutcome.SKIPPED
            else -> return null
        }
        val alreadyWoven = map["alreadyWoven"] as? Boolean ?: false
        val skipReason = (map["skipReason"] as? String)
            ?.let { name -> WeaveSkipReason.entries.firstOrNull { it.name == name } }
        val wovenMethods = (map["wovenMethods"] as? List<*>)
            ?.mapNotNull { toWovenMethod(it) }
            ?: emptyList()
        return ClassWeaveDecision(
            className = className,
            outcome = outcome,
            alreadyWoven = alreadyWoven,
            skipReason = skipReason,
            wovenMethods = wovenMethods,
        )
    }

    private fun toWovenMethod(value: Any?): WovenMethod? {
        val map = value as? Map<*, *> ?: return null
        val methodName = map["methodName"] as? String ?: return null
        val descriptor = map["descriptor"] as? String ?: return null
        val matchedAdvices = (map["matchedAdvices"] as? List<*>)
            ?.mapNotNull { it as? String }
            ?: emptyList()
        return WovenMethod(methodName, descriptor, matchedAdvices)
    }

    private fun serialize(value: Any?, depth: Int = 0): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> serializeObject(value, depth)
        is Iterable<*> -> serializeArray(value, depth)
        else -> quote(value.toString())
    }

    private fun serializeObject(value: Map<*, *>, depth: Int): String {
        if (value.isEmpty()) return "{}"
        val indent = indent(depth)
        val nestedIndent = indent(depth + 1)
        val body = value.entries.joinToString(",\n") { (key, nestedValue) ->
            "$nestedIndent${quote(key.toString())}: ${serialize(nestedValue, depth + 1)}"
        }
        return "{\n$body\n$indent}"
    }

    private fun serializeArray(value: Iterable<*>, depth: Int): String {
        val items = value.toList()
        if (items.isEmpty()) return "[]"
        val indent = indent(depth)
        val nestedIndent = indent(depth + 1)
        val body = items.joinToString(",\n") { item -> "$nestedIndent${serialize(item, depth + 1)}" }
        return "[\n$body\n$indent]"
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    private fun indent(depth: Int): String = "  ".repeat(depth)

    /** 极简递归下降 JSON 解析器（本模块只用于读回自己写出的计划文件）。 */
    private class JsonParser(private val text: String) {
        private var index: Int = 0

        fun parseRoot(): Any? {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            check(index < text.length) { "unexpected end of json at $index" }
            return when (val char = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> {
                    check(char == '-' || char.isDigit()) { "unexpected char '$char' at $index" }
                    parseNumber()
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = parseValue()
                skipWhitespace()
                when (val char = peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }

                    else -> throw IllegalStateException("expected ',' or '}' but found '$char' at $index")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                result.add(parseValue())
                skipWhitespace()
                when (val char = peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }

                    else -> throw IllegalStateException("expected ',' or ']' but found '$char' at $index")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                check(index < text.length) { "unterminated string at $index" }
                when (val char = text[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        check(index < text.length) { "unterminated escape at $index" }
                        when (val escaped = text[index++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                val hex = text.substring(index, index + 4)
                                index += 4
                                builder.append(hex.toInt(16).toChar())
                            }

                            else -> throw IllegalStateException("invalid escape '\\$escaped' at $index")
                        }
                    }

                    else -> builder.append(char)
                }
            }
        }

        private fun parseNumber(): Number {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] == '.' || text[index] == 'e' || text[index] == 'E' || text[index] == '+' || text[index] == '-')) {
                index++
            }
            val raw = text.substring(start, index)
            return raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            check(text.startsWith(literal, index)) { "expected '$literal' at $index" }
            index += literal.length
            return value
        }

        private fun expect(char: Char) {
            check(index < text.length && text[index] == char) { "expected '$char' at $index" }
            index++
        }

        private fun peek(): Char {
            check(index < text.length) { "unexpected end of json at $index" }
            return text[index]
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) {
                index++
            }
        }
    }
}
