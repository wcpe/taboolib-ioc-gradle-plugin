package top.wcpe.taboolib.ioc.gradle.analysis

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal object SourceLocationIndexBuilder {

    private val packageRegex = Regex("""^\s*package\s+([A-Za-z0-9_.]+)\s*;?\s*$""")

    fun build(sourceDirectories: Iterable<Path>): SourceLocationIndex {
        val entries = mutableListOf<SourceClassEntry>()
        sourceDirectories.filter { Files.exists(it) }.distinct().forEach { directory ->
            val sourceRoot = directory.toAbsolutePath().normalize()
            Files.walk(sourceRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.toString().endsWith(".kt")) }
                    .forEach { file -> entries += scanFile(file, sourceRoot) }
            }
        }
        return selectEntries(entries)
    }

    /**
     * 同名 FQCN 的优胜者必须与文件系统遍历顺序无关：`Files.walk` 的产出顺序由底层目录实现决定，
     * 而本任务受 build cache 管理——同一份源码产出内容不同的索引会让缓存键失去意义。
     *
     * 冲突时按 `filePath` 字典序取稳定优胜者（排序保持稳定，同文件内的条目相对顺序不变），
     * 并把冲突记成可读的降级说明存到索引对象里：只落在索引上，不打日志、不改报告结构。
     */
    private fun selectEntries(entries: List<SourceClassEntry>): SourceLocationIndex {
        val winners = linkedMapOf<String, SourceClassEntry>()
        val degradationNotes = mutableListOf<String>()
        entries.sortedBy { it.stableOrderKey() }.forEach { entry ->
            val previous = winners.putIfAbsent(entry.className, entry)
            // 只要发生同名冲突就记一条：此前用「相对路径不同」做条件，而多个源码根（提交
            // attachProjectDependencySources 正是把多个模块源码并进同一份 sourceDirectories）
            // 下同名相对路径会让这条静默消失 —— 那正是「报告为什么变了」最需要解释的场景。
            if (previous != null) {
                degradationNotes += "同名类型 ${entry.className} 同时出现在 ${previous.filePath} 与 ${entry.filePath}，" +
                    "已按路径字典序选用 ${previous.filePath}"
            }
        }
        return SourceLocationIndex(winners, degradationNotes)
    }

    /**
     * 排序键统一用 `/` 分隔：同一份源码在 Windows 与 Unix 上必须排出同一个优胜者，
     * 否则两边的工作目录缓存仍然会各自失效。
     */
    private fun SourceClassEntry.stableOrderKey(): String = filePath.toString().replace('\\', '/')

    private fun scanFile(file: Path, sourceRoot: Path): List<SourceClassEntry> {
        val kotlinSource = file.toString().endsWith(".kt")
        // Java 没有字符串模板：`.java` 里的 `"${"` 是普通字面量（例如表示占位符前缀）。
        // 若照样按模板处理，`${` 之后的引号会被当成模板内字符串的起点，整个文件剩余部分被屏蔽，
        // 花括号配对随之失败、`depths` 被抬高，导致后续**所有顶层类型**拿不到 className 而消失。
        val lines = maskNonCodeSegments(readSourceLines(file), templatesEnabled = kotlinSource)
        val packageName = lines.firstNotNullOfOrNull { packageRegex.matchEntire(it)?.groupValues?.getOrNull(1) }.orEmpty()
        val source = lines.joinToString("\n")
        val scopes = SourceTypeScopeScanner(source, packageName, kotlinSource).scan()
        return scopes.mapNotNull { scope ->
            val name = scope.className ?: return@mapNotNull null
            SourceClassEntry(name, scope.simpleName, sourceRoot.relativize(file.toAbsolutePath().normalize()),
                ownedLines(source, scope, scopes), lineAt(source, scope.start), lineAt(source, scope.end), scope.isCompanion)
        }
    }

    private fun ownedLines(source: String, owner: SourceTypeScope, scopes: List<SourceTypeScope>): List<String> {
        val characters = source.toCharArray()
        maskRange(characters, 0 until owner.start)
        maskRange(characters, owner.end + 1 until characters.size)
        scopes.filter { owner.contains(it) }.forEach { maskRange(characters, it.start..it.end) }
        return String(characters).split('\n')
    }

    private fun maskRange(characters: CharArray, range: IntRange) {
        range.forEach { if (characters[it] != '\n') characters[it] = ' ' }
    }

    private fun lineAt(source: String, offset: Int): Int = source.take(offset).count { it == '\n' } + 1
}

/** 行分隔符匹配（覆盖 CRLF / CR / LF）：与 [Files.readAllLines] 一样吞掉分隔符本身。 */
private val sourceLineBreakRegex = Regex("""\r\n|\r|\n""")

/**
 * 宽容解码源码文本。
 *
 * 仓库里混入 GBK / ANSI 编码的历史源码时，严格 UTF-8 解码会抛 `MalformedInputException`，
 * 而调用链上没有任何 catch：整个分析任务会以**不带文件名**的错误中断，根本定位不到是哪个文件。
 * 这里改用 REPLACE 策略把非法字节替换掉，单个坏文件不再波及其余文件的索引。
 */
internal fun readSourceText(file: Path): String {
    return try {
        val bytes = Files.newInputStream(file).use { it.readBytes() }
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            // 首部 BOM 必须剥掉：行首的 U+FEFF 既不匹配 `\s`（Java 的 \s 是 ASCII 白名单），
            // 也不被解码器丢弃，于是 `^\s*package` 失配 → 整份文件拿到空包名 → 所有类型的 FQCN
            // 退化成简单名 → 诊断的抑制全面失效，合法字段被报成缺失注入（ERROR，默认阻断构建）。
            // 带 BOM 的文件是合法输入（编译器自身会忽略 BOM），不能当异常处理。
            .removePrefix("﻿")
    } catch (exception: IOException) {
        // 真正的读取失败（文件被删 / 无权限）仍要抛，但必须把路径带进异常信息。
        throw IOException("读取源码文件失败：$file", exception)
    }
}

/**
 * 按行读取源码，语义与 [Files.readAllLines] 对齐（换行兼容 CRLF / CR）。
 *
 * 末尾的行分隔符不产生额外空行：若保留，文件末尾会平白多出一个字符，EOF 边界的行号判定整体偏移。
 */
internal fun readSourceLines(file: Path): List<String> {
    val lines = readSourceText(file).split(sourceLineBreakRegex)
    return if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
}

private data class SourceTypeScope(
    val simpleName: String,
    val start: Int,
    val end: Int,
    val bodyStart: Int?,
    val bodyDepth: Int,
    val className: String?,
    val isCompanion: Boolean,
) {
    fun contains(other: SourceTypeScope): Boolean = other.start > start && other.end <= end
    fun containsPosition(offset: Int): Boolean = bodyStart != null && offset > bodyStart && offset < end
}

private class SourceTypeScopeScanner(
    private val source: String,
    private val packageName: String,
    private val kotlinSource: Boolean,
) {

    // 按语言分派：`object` / `companion` / `enum class` 是 Kotlin 独有。`object` 在 Java 里
    // 根本不是关键字——`for (Service object : list)`、`x ? object : y` 里的 `object` 是普通
    // 标识符；若照 Kotlin 规则当成类型声明，会凭空生成一个**带类体**的作用域，把循环体从外层
    // 的 ownedLines 里屏蔽掉，吞掉真实的手工赋值，让合法字段被误报缺失注入。
    private val typeRegex = if (kotlinSource) {
        // 无名 `object` 必须紧跟 `:`（`object : X`）、`{`（匿名对象 / 伴生对象）或行尾
        // （`object Singleton` 这类无类体写法），否则会把名为 `object` 的成员误认成类型声明。
        Regex(
            """\b(?:(?:enum\s+class|class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)|""" +
                """(?:(companion)\s+)?object(?:\s+([A-Za-z_][A-Za-z0-9_]*))?(?=\s*(?:[:{]|$)))""",
            RegexOption.MULTILINE,
        )
    } else {
        Regex("""\b(?:class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    }
    private val anonymousRegex = Regex("""\bnew\s+[A-Za-z_][A-Za-z0-9_.$]*(?:<[^{};]*>)?\s*(?=\()""")

    /**
     * 每一列之前已打开的 `{` 数量。用于判定「成员嵌套类」：只有
     * `depth == parent.bodyDepth + 1` 才算直接写在父类体里的类型；函数体 / init 块 /
     * lambda 里多一层括号的类型会被拒。
     */
    private val depths = IntArray(source.length + 1).also { array ->
        var open = 0
        source.forEachIndexed { index, character ->
            array[index] = open
            when (character) {
                '{' -> open++
                '}' -> open = maxOf(0, open - 1)
            }
        }
    }

    fun scan(): List<SourceTypeScope> {
        val scopes = mutableListOf<SourceTypeScope>()
        val active = ArrayDeque<SourceTypeScope>()
        val matches = (typeRegex.findAll(source) + anonymousRegex.findAll(source)).sortedBy { it.range.first }
        matches.forEach { match ->
            while (active.isNotEmpty() && active.last().end < match.range.first) active.removeLast()
            val parent = active.lastOrNull()?.takeIf { it.containsPosition(match.range.first) }
            val scope = scopeOf(match, parent) ?: return@forEach
            scopes += scope
            if (scope.bodyStart != null) active.addLast(scope)
        }
        return scopes
    }

    private fun scopeOf(match: MatchResult, parent: SourceTypeScope?): SourceTypeScope? {
        val start = match.range.last + 1
        val header = if (match.value.startsWith("new")) anonymousHeader(start) else headerRange(start)
        if (header == null) return null
        // 越界守卫：类型名正好落在源码末尾时 header 会退化成最后一个字符，此处不得再索引 source。
        val bodyStart = header.first.takeIf { it < source.length && source[it] == '{' }
        // 花括号配对失败（源码本身不配平）时**不能整条丢弃**：丢弃会让该类型及其所有嵌套类型
        // 一起从索引里消失，`analyzeField` 返回 null 后候选不再被抑制，反而把合法代码误报成
        // 缺失注入。退化为「只保留类头范围」——不覆盖任何类体行，因此不会凭空抑制真实错误。
        val end = if (bodyStart == null) header.second else matchingBrace(bodyStart) ?: header.second
        val companion = match.groupValues.getOrNull(2) == "companion"
        val name = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "Companion".takeIf { companion }
        val depth = depths[match.range.first]
        val className = when {
            name == null -> null
            parent != null && depth == parent.bodyDepth + 1 -> parent.className?.let { "$it\$$name" }
            parent == null && depth == 0 -> if (packageName.isBlank()) name else "$packageName.$name"
            else -> null
        }
        return SourceTypeScope(name.orEmpty(), match.range.first, end, bodyStart,
            bodyStart?.let { depths[it] } ?: depth, className, companion)
    }

    /**
     * 从 [bodyStart] 起按**局部计数**找配对的 `}`。
     *
     * 刻意不用全局配对表：那样一处不配平会让整份文件的所有类型一起失败。局部计数只影响该类型
     * 自身，且返回 null 时上层可退化为类头范围，而不是把类型丢掉。
     */
    private fun matchingBrace(bodyStart: Int): Int? {
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return null
    }

    private fun headerRange(start: Int): Pair<Int, Int>? {
        var parentheses = 0
        var angles = 0
        // 最近一个顶层 `by` 的起点，用于识别 `class A : B by object : B { ... } { ... }`
        // 这种「委托表达式自带花括号」的写法。
        var delegateKeywordAt = -1
        var index = start
        while (index < source.length) {
            when (source[index]) {
                '(' -> parentheses++
                ')' -> if (parentheses > 0) parentheses--
                '<' -> if (parentheses == 0) angles++
                '>' -> if (parentheses == 0 && angles > 0 && source.getOrNull(index - 1) != '-') angles--
                'b' -> if (parentheses == 0 && angles == 0 && isWordAt("by", index)) delegateKeywordAt = index
                '{' -> if (parentheses == 0 && angles == 0) {
                    // 委托表达式自带的 `{}`（`by lazy { ... }`、`by object : X { ... }`）：
                    // 整对跳过，类体在它后面。
                    if (delegateKeywordAt < 0 || !isDelegateBraceGroup(index)) return index to index
                    val delegateEnd = matchingBrace(index) ?: return index to index
                    delegateKeywordAt = -1
                    index = delegateEnd
                }

                ';' -> if (parentheses == 0 && angles == 0) return start to index

                '\n' -> if (parentheses == 0 && angles == 0 && !headerContinuesAt(index)) return start to (index - 1)
            }
            index++
        }
        // 类型名正好是源码末尾时不能再返回 null——那会让该类型从索引里静默消失。
        return (if (start < source.length) start else source.lastIndex) to source.lastIndex
    }

    /**
     * [open] 这一对花括号是否属于 `by` 之后的**委托表达式**，而不是类体。
     *
     * 判据：它的配对 `}` 之后、在下一条声明或文件结束之前，还存在**另一个**顶层 `{`。
     * 于是 `by delegate { ... }`（委托是普通表达式，花括号就是类体）与
     * `by lazy { ... } { ... }` / `by run { ... } { ... }` / `by object : X { ... } { ... }`
     * （花括号先属于委托表达式）都能区分开。
     *
     * 刻意不用「委托写法白名单」：白名单只能修到被列举的那一种，其余任何一种都会让委托体的
     * 花括号被当成类体，真实类体随之被 `ownedLines` 屏蔽，合法字段被误报成缺失注入。
     */
    private fun isDelegateBraceGroup(open: Int): Boolean {
        val close = matchingBrace(open) ?: return false
        var index = close + 1
        var lineStart = index
        var parentheses = 0
        var angles = 0
        var scannedLines = 0
        // 上限按行计：类头 / 委托体天然是行结构，按字符计会把合法但较长的写法截断。
        // 字符上限只作病态输入（整份文件挤在一行）的兜底。
        val limit = minOf(source.length, index + HEADER_LOOKAHEAD_CHARS)
        while (index < limit) {
            when (source[index]) {
                '(' -> parentheses++
                ')' -> if (parentheses > 0) parentheses--
                '<' -> if (parentheses == 0) angles++
                '>' -> if (parentheses == 0 && angles > 0 && source.getOrNull(index - 1) != '-') angles--
                '{' -> if (parentheses == 0 && angles == 0) {
                    // 声明关键字与左花括号**同行**时，这个花括号属于新声明，不是委托表达式体。
                    // 熔断只在 '\n' 分支求值，同行形态永远等不到，必须在返回前先判：
                    // 否则 `class Holder : Base by impl {` 会把下一条声明的类体（`class Other {`）
                    // 当成自己的委托体，Holder 的成员类型整体丢条目，其中的合法字段被误报缺失注入。
                    // 合法的 `} {` 形态（`by lazy { } { }`）行内只有空白，不受影响。
                    if (startsNewDeclaration(lineStart, index)) return false
                    return true
                }
                '\n' -> {
                    // 只在**括号与尖括号都已闭合**的行上计数：构造器参数列表、多行泛型实参逐行展开时
                    // 每行都算的话，80 行预算会被一个正常的长参数列表耗尽，导致类体整体失联。
                    if (parentheses == 0 && angles == 0) {
                        if (startsNewDeclaration(lineStart, index)) return false
                        if (++scannedLines >= HEADER_LOOKAHEAD_LINES) return false
                    }
                    lineStart = index + 1
                }
            }
            index++
        }
        return false
    }

    private fun anonymousHeader(start: Int): Pair<Int, Int>? {
        var parentheses = 0
        for (index in start until source.length) {
            if (source[index] == '(') parentheses++
            if (source[index] == ')' && --parentheses == 0) {
                val next = (index + 1 until source.length).firstOrNull { !source[it].isWhitespace() } ?: return null
                return if (source[next] == '{') next to next else null
            }
        }
        return null
    }

    private fun isWordAt(word: String, index: Int): Boolean {
        if (!source.startsWith(word, index)) return false
        val before = source.getOrNull(index - 1)
        val after = source.getOrNull(index + word.length)
        return !isWordCharacter(before) && !isWordCharacter(after)
    }

    private fun isWordCharacter(character: Char?): Boolean =
        character != null && (character.isLetterOrDigit() || character == '_')

    /**
     * 类头在 [newline] 处是否应当结束。
     *
     * 旧实现用「续行关键字白名单」（`:`、`,`、`extends `、`implements `）。任何未列举的合法
     * 换行——`class Foo` 换行接 `@Inject constructor(...)`、`where` 子句、裸父类型列表——都会
     * 让类头被误判为结束：`bodyStart` 变 null，**整个类体不在该条目范围内**，字段初值与手工
     * 装配事实全部丢失，合法代码被误报 `missing-inject-annotation` 并中断构建。
     *
     * 改为「有界回溯 + 新声明熔断」：向后续行找类体起点（`{`）或声明结束（`;`）；一旦遇到
     * 另一条类型 / 函数 / 属性声明就立即熔断——否则无类体的 `class Bodyless` 会吞掉紧随其后的
     * `class Outer {`。回溯上限见 [HEADER_LOOKAHEAD_LINES] 与 [HEADER_LOOKAHEAD_CHARS]。
     */
    private fun headerContinuesAt(newline: Int): Boolean {
        var parentheses = 0
        var angles = 0
        var lineStart = newline + 1
        var index = newline + 1
        var scannedLines = 0
        val limit = minOf(source.length, newline + HEADER_LOOKAHEAD_CHARS)
        while (index < limit) {
            when (source[index]) {
                '(' -> parentheses++
                ')' -> if (parentheses > 0) parentheses--
                '<' -> if (parentheses == 0) angles++
                '>' -> if (parentheses == 0 && angles > 0 && source.getOrNull(index - 1) != '-') angles--
                '{' -> if (parentheses == 0 && angles == 0) {
                    // 同 `isDelegateBraceGroup`：声明关键字与左花括号同行时该花括号属于新声明，
                    // 无类体类型（`class Bodyless` 后接 `fun helper() { ... }`）不得把它吞成自己的类体，
                    // 否则函数内的局部类会被挂成 `Bodyless$Local` 这样的幻影嵌套名。
                    if (startsNewDeclaration(lineStart, index)) return false
                    return true
                }
                ';' -> if (parentheses == 0 && angles == 0) return false
                '\n' -> {
                    // 只在**括号与尖括号都已闭合**的行上计数：构造器参数列表、多行泛型实参逐行展开时
                    // 每行都算的话，80 行预算会被一个正常的长参数列表耗尽，类体整体失联后合法字段
                    // 会被误报缺失注入（ERROR，默认阻断构建），而且这条退化原本没有任何痕迹。
                    if (parentheses == 0 && angles == 0) {
                        if (startsNewDeclaration(lineStart, index)) return false
                        if (++scannedLines >= HEADER_LOOKAHEAD_LINES) return false
                    }
                    lineStart = index + 1
                }
            }
            index++
        }
        return false
    }

    private fun startsNewDeclaration(from: Int, to: Int): Boolean {
        val line = source.substring(from, to)
        return declarationStarterRegex.containsMatchIn(line) || line.trimStart().startsWith("}")
    }

    private companion object {

        /**
         * 类头 / 委托体最多向后回溯的**行数**，超过即判定「不续行 / 不是委托体」。
         *
         * 类头天然是行结构（注解、父类型列表、`where` 子句、构造器参数各自占行），按**字符**计会
         * 把合法但较长的类头截断：判定「不续行」后 `bodyStart` 置空，整个类体落在条目范围之外，
         * 类内字段全部不可见，合法字段被误报 `missing-inject-annotation`。
         */
        const val HEADER_LOOKAHEAD_LINES = 80

        /** 病态单行输入（例如整份文件挤在一行）的字符兜底上限，避免行数判据失效时无界扫描。 */
        const val HEADER_LOOKAHEAD_CHARS = 20_000

        /**
         * 判定「这一行是一条新声明」：先剥掉前置注解与修饰符，再要求出现声明关键字。
         *
         * 注解允许带**站点目标**（`@get:` / `@set:` / `@field:` / `@delegate:`）：`@get:Inject val x`
         * 这类合法声明行若吃不下，熔断就不触发，前面无类体的类型会把后续类体的花括号吞成自己的，
         * 真实类型随之变成 `Bodyless$Outer` 这样的幻影嵌套名。
         *
         * 行首 `context(...)`（Kotlin 上下文接收者）同理必须算作声明的前置部分。
         *
         * 刻意**不**包含 `constructor`：`@Inject constructor(...)` 正是类头的合法续行，把它
         * 算作新声明会让本函数要修的 bug 原样复现。也刻意不包含裸 `by`：`class Foo : Bar` 换行接
         * `by delegate {` 是合法类头续行，把 `by` 当声明关键字会让类头提前结束。委托属性
         * （`val x by ...`）本身以 `val` / `var` 开头，已由关键字表覆盖。
         */
        val declarationStarterRegex = Regex(
            """^\s*(?:@[A-Za-z_][A-Za-z0-9_.]*(?::[A-Za-z_][A-Za-z0-9_]*)?(?:\([^)]*\))?\s*)*""" +
                """(?:(?:context\s*\([^)]*\)|(?:public|private|protected|internal|static|final|abstract|open|sealed|data|value|""" +
                """inline|suspend|operator|override|lateinit|const|external|actual|expect|tailrec|infix|""" +
                """vararg|annotation))\s+)*""" +
                """(?:class|interface|enum|record|object|fun|val|var|typealias)\b""",
        )
    }
}

internal data class SourceLocation(
    val sourcePath: String,
    val sourceLine: Int,
    val sourceColumn: Int,
)

internal data class SourceFieldAnalysis(
    val location: SourceLocation,
    val hasInitializer: Boolean,
    val hasManualAssignment: Boolean,
)

internal class SourceLocationIndex(
    private val classEntries: Map<String, SourceClassEntry>,
    /**
     * 索引构建期的降级说明（例如同名类型冲突的裁决结果）。
     *
     * 只保存在索引对象里供上层按需读取：不打日志、不改报告结构，避免可观测性实现被索引内部细节绑架。
     */
    val degradationNotes: List<String> = emptyList(),
) {

    fun resolve(injectionPoint: InjectionPointDefinition): SourceLocation? {
        val classEntry = classEntries[injectionPoint.ownerClassName] ?: return null
        classEntry.locate(injectionPoint)?.let { return it }
        if (injectionPoint.kind == InjectionPointKind.FIELD) {
            val companion = classEntries[injectionPoint.ownerClassName + "\$Companion"]?.takeIf { it.isCompanionObject }
            companion?.locate(injectionPoint)?.let { return it }
        }
        return classEntry.resolve(injectionPoint)
    }

    fun analyzeField(injectionPoint: InjectionPointDefinition): SourceFieldAnalysis? {
        if (injectionPoint.kind != InjectionPointKind.FIELD) {
            return null
        }
        val classEntry = classEntries[injectionPoint.ownerClassName] ?: return null
        return classEntry.analyzeField(injectionPoint)
    }
}

internal data class SourceClassEntry(
    val className: String,
    val simpleName: String,
    val filePath: Path,
    val lines: List<String>,
    val startLine: Int,
    val endLine: Int,
    val isCompanionObject: Boolean = false,
) {

    private val maskedLines: List<String> by lazy { maskNonCodeSegments(lines) }

    /**
     * 按「用途 + 名字」缓存带变量的正则。
     *
     * `fieldSuffix` 在收集字段声明时逐行调用、`locate` 会对每个候选类的每一行调用，每次都
     * `Regex(...)` 新建等价于在热路径上重复编译并产生大量短命对象。用 `ConcurrentHashMap`
     * 而非普通可变表：类条目可能被并行构建（字节码侧就是并行扫描）。
     */
    private val namePatterns = ConcurrentHashMap<String, Regex>()

    private fun namePattern(key: String, build: () -> Regex): Regex = namePatterns.getOrPut(key) { build() }

    fun resolve(injectionPoint: InjectionPointDefinition): SourceLocation = locate(injectionPoint) ?: SourceLocation(
        sourcePath = filePath.toString(),
        sourceLine = startLine,
        sourceColumn = maskedLines.getOrNull(startLine - 1)?.indexOf(simpleName)?.takeIf { it >= 0 }?.plus(1) ?: 1,
    )

    fun locate(injectionPoint: InjectionPointDefinition): SourceLocation? {
        val declarationName = injectionPoint.declarationName
        val pattern = when (injectionPoint.kind) {
            InjectionPointKind.CONSTRUCTOR_PARAMETER ->
                namePattern("constructor:$simpleName") { Regex("""\b${Regex.escape(simpleName)}\s*\(""") }

            InjectionPointKind.METHOD_PARAMETER ->
                namePattern("method:$declarationName") { Regex("""\b${Regex.escape(declarationName)}\s*\(""") }

            // `=(?!=)` 排除 `==`：否则 `field == null` 这类**比较**行会被当成字段声明行，
            // 之后所有初值/手工装配判定都建立在错误的锚点上。`:(?!:)` 同理排除 `::`。
            InjectionPointKind.FIELD -> namePattern("field:$declarationName") {
                Regex("""\b${Regex.escape(declarationName)}\b(?=\s*(?:;|:(?!:)|=(?!=)))""")
            }
        }
        for (lineNumber in startLine..endLine) {
            val line = maskedLines.getOrNull(lineNumber - 1) ?: continue
            val match = pattern.find(line) ?: continue
            val column = match.range.first + 1
            return SourceLocation(
                sourcePath = filePath.toString(),
                sourceLine = lineNumber,
                sourceColumn = column,
            )
        }
        return null
    }

    fun analyzeField(injectionPoint: InjectionPointDefinition): SourceFieldAnalysis {
        val location = resolve(injectionPoint)
        val declaration = collectFieldDeclaration(injectionPoint.declarationName, location.sourceLine)
        return SourceFieldAnalysis(
            location = location,
            hasInitializer = declaration.hasInitializer,
            hasManualAssignment = hasManualAssignment(injectionPoint.declarationName, declaration.endLine),
        )
    }

    private fun collectFieldDeclaration(fieldName: String, fieldLine: Int): FieldDeclarationSnippet {
        val builder = StringBuilder()
        var nesting = NestingState()
        val maxLine = minOf(endLine, fieldLine + 40)
        var declarationEndLine = fieldLine
        for (lineNumber in fieldLine..maxLine) {
            val line = maskedLines.getOrNull(lineNumber - 1).orEmpty()
            builder.append(line).append('\n')
            nesting = nesting.update(line)
            declarationEndLine = lineNumber

            val declarationText = builder.toString()
            val fieldSuffix = declarationText.fieldSuffix(fieldName)
            val hasInitializer = fieldSuffix?.containsRealAssignment() == true
            val hasDelegate = fieldSuffix?.containsDelegateKeyword() == true
            val hasInitializerOrDelegate = hasInitializer || hasDelegate

            if (nesting.isBalanced() && line.contains(';')) {
                break
            }
            if (!hasInitializerOrDelegate) {
                break
            }

            val nextMeaningfulLine = peekNextMeaningfulLine(lineNumber)
            if (nesting.isBalanced() &&
                !line.trimEnd().endsWithExpressionContinuation() &&
                !nextMeaningfulLine.startsWithExpressionContinuation()
            ) {
                break
            }
        }
        val declarationText = builder.toString()
        val fieldSuffix = declarationText.fieldSuffix(fieldName)
        return FieldDeclarationSnippet(
            endLine = declarationEndLine,
            hasInitializer = fieldSuffix?.let { it.containsRealAssignment() || it.containsDelegateKeyword() } == true,
        )
    }

    private fun hasManualAssignment(fieldName: String, declarationEndLine: Int): Boolean {
        if (declarationEndLine >= endLine) {
            return false
        }
        val codeAfterDeclaration = maskedLines.subList(declarationEndLine, endLine).joinToString("\n")
        // 只认「成员位置」的赋值，否则同名局部变量/其它对象的同名属性会把真实缺失注入
        // 静默抑制掉（漏报，正是本文件要消灭的方向）：
        //  - 左边界不允许 `.`：排除 `other.field = ...`；`this.field = ...` 单列一条分支
        //  - 排除 `val field = ...` / `var field = ...` / `<类型> field = ...` 这类同名局部声明
        // 收紧的代价是可能多报（真实漏注入被报出来），比放松导致漏报安全。
        val assignmentPattern = namePattern("assignment:$fieldName") {
            val name = Regex.escape(fieldName)
            Regex(
                """(?s)(?:""" +
                    """(?<![A-Za-z0-9_.])this\.$name\s*(?:[+\-*/%&|^]?=)(?![=])""" +
                    """|""" +
                    // `this@Foo.service = ...`：在 lambda / 匿名对象 / 伴生对象里显式限定到外层实例的
                    // 装配。它是**真实装配**，必须识别；上面那条「左边界不允许 `.`」的规则会把
                    // `this@Foo.` 的点号一起排除掉，所以单列一条。
                    """(?<![A-Za-z0-9_.])this@[A-Za-z_][A-Za-z0-9_]*\.$name\s*(?:[+\-*/%&|^]?=)(?![=])""" +
                    """|""" +
                    """(?<![A-Za-z0-9_.])(?<!\bval\s)(?<!\bvar\s)(?<!\w\s)$name\s*(?:[+\-*/%&|^]?=)(?![=])""" +
                    """)""",
            )
        }
        return assignmentPattern.containsMatchIn(codeAfterDeclaration)
    }

    private fun peekNextMeaningfulLine(currentLine: Int): String {
        for (lineNumber in currentLine + 1..endLine) {
            val trimmed = maskedLines.getOrNull(lineNumber - 1)?.trim().orEmpty()
            if (trimmed.isNotEmpty()) {
                return trimmed
            }
        }
        return ""
    }

    private fun String.fieldSuffix(fieldName: String): String? {
        val fieldMatch = namePattern("suffix:$fieldName") { Regex("""\b${Regex.escape(fieldName)}\b""") }.find(this)
            ?: return null
        return substring(fieldMatch.range.last + 1)
    }

    private fun String.containsRealAssignment(): Boolean {
        val assignmentIndex = indexOf('=')
        if (assignmentIndex < 0) {
            return false
        }
        val previous = getOrNull(assignmentIndex - 1)
        val next = getOrNull(assignmentIndex + 1)
        return previous !in listOf('=', '!', '<', '>') && next != '='
    }

    private fun String.containsDelegateKeyword(): Boolean = delegateKeywordRegex.containsMatchIn(this)

    private fun String.endsWithExpressionContinuation(): Boolean {
        val trimmed = trimEnd()
        if (trimmed.isEmpty()) {
            return false
        }
        if (trimmed.endsWith("=") || trimmed.endsWith("by")) {
            return true
        }
        return continuationEndTokens.any { trimmed.endsWith(it) }
    }

    private fun String.startsWithExpressionContinuation(): Boolean {
        val trimmed = trimStart()
        if (trimmed.isEmpty()) {
            return false
        }
        return continuationStartTokens.any { trimmed.startsWith(it) }
    }

    private data class FieldDeclarationSnippet(
        val endLine: Int,
        val hasInitializer: Boolean,
    )

    private data class NestingState(
        val parentheses: Int = 0,
        val brackets: Int = 0,
        val braces: Int = 0,
    ) {
        fun update(line: String): NestingState {
            var parenthesesDepth = parentheses
            var bracketsDepth = brackets
            var bracesDepth = braces
            line.forEach { character ->
                when (character) {
                    '(' -> parenthesesDepth += 1
                    ')' -> parenthesesDepth = (parenthesesDepth - 1).coerceAtLeast(0)
                    '[' -> bracketsDepth += 1
                    ']' -> bracketsDepth = (bracketsDepth - 1).coerceAtLeast(0)
                    '{' -> bracesDepth += 1
                    '}' -> bracesDepth = (bracesDepth - 1).coerceAtLeast(0)
                }
            }
            return copy(
                parentheses = parenthesesDepth,
                brackets = bracketsDepth,
                braces = bracesDepth,
            )
        }

        fun isBalanced(): Boolean {
            return parentheses == 0 && brackets == 0 && braces == 0
        }
    }

    private companion object {
        /** `by` 关键字探测：逐行调用，必须复用同一个 `Regex` 实例而不是每次新建。 */
        val delegateKeywordRegex = Regex("""\bby\b""")

        val continuationEndTokens = listOf(
            ".",
            "?.",
            "?:",
            ",",
            "+",
            "-",
            "*",
            "/",
            "%",
            "&&",
            "||",
            "(",
            "[",
            "{",
            "->",
        )
        val continuationStartTokens = listOf(
            ".",
            "?.",
            "?:",
            ")",
            "]",
        )
    }
}

private fun maskNonCodeSegments(sourceLines: List<String>, templatesEnabled: Boolean = true): List<String> {
    val source = sourceLines.joinToString("\n")
    val masked = StringBuilder(source.length)
    var index = 0
    var state = LexicalState.CODE
    var escape = false
    // 进入任何嵌套结构前把「当前状态」压栈，退出时弹回。Kotlin 的 `${...}` 里可以再出现字符串
    // 字面量：若一律回到 CODE，模板内的 `"` 会被当成新字符串起点、模板里的 `}` 会被当成代码里的
    // 花括号。幻影花括号会让花括号配对错位，把整个文件的类型从索引里抹掉或写成错误的嵌套名。
    val returnStates = ArrayDeque<LexicalState>()
    // Kotlin 的块注释可以嵌套（`/* /* */ */`），单层计数会在第一个 `*/` 就提前退出。
    var blockCommentDepth = 0
    // 每层模板表达式各自的 `{` 配对深度。**必须按层压栈**，不能用单一计数器：内层 `${` 会把外层
    // 已开的计数覆盖掉，导致退出提前发生、状态机在字符串字面量**内部**回到 CODE；此后模板里的
    // 真实换行会命中 STRING 的 `\n` 分支再退出一次，字面量剩余部分被当代码扫描，其中的花括号
    // 泄漏成幻影花括号 → 类体失联 → 合法字段被误报缺失注入。
    val templateDepths = ArrayDeque<Int>()

    fun enter(next: LexicalState) {
        returnStates.addLast(state)
        state = next
    }

    fun exit() {
        state = returnStates.removeLastOrNull() ?: LexicalState.CODE
        // 必须复位转义标记：否则进入嵌套状态之前的那个 `\` 会一直生效，把嵌套结构真正的
        // 结束引号当成「被转义的引号」吞掉，字符串一路跑到行尾才恢复。
        escape = false
    }

    while (index < source.length) {
        val current = source[index]
        val next = source.getOrNull(index + 1)
        val nextTwo = source.getOrNull(index + 2)
        when (state) {
            LexicalState.CODE -> when {
                current == '/' && next == '/' -> {
                    masked.append("  ")
                    index += 2
                    enter(LexicalState.LINE_COMMENT)
                }

                current == '/' && next == '*' -> {
                    masked.append("  ")
                    index += 2
                    blockCommentDepth = 1
                    enter(LexicalState.BLOCK_COMMENT)
                }

                current == '"' && next == '"' && nextTwo == '"' -> {
                    masked.append("   ")
                    index += 3
                    enter(LexicalState.TRIPLE_QUOTED_STRING)
                }

                current == '"' -> {
                    masked.append(' ')
                    index += 1
                    escape = false
                    enter(LexicalState.STRING)
                }

                current == '\'' -> {
                    masked.append(' ')
                    index += 1
                    escape = false
                    enter(LexicalState.CHAR)
                }

                else -> {
                    masked.append(current)
                    index += 1
                }
            }

            LexicalState.LINE_COMMENT -> {
                if (current == '\n') {
                    masked.append('\n')
                    index += 1
                    exit()
                } else {
                    masked.append(' ')
                    index += 1
                }
            }

            LexicalState.BLOCK_COMMENT -> when {
                current == '/' && next == '*' -> {
                    masked.append("  ")
                    index += 2
                    blockCommentDepth++
                }

                current == '*' && next == '/' -> {
                    masked.append("  ")
                    index += 2
                    blockCommentDepth--
                    if (blockCommentDepth <= 0) exit()
                }

                else -> {
                    masked.append(if (current == '\n') '\n' else ' ')
                    index += 1
                }
            }

            LexicalState.STRING -> when {
                current == '\\' && !escape -> {
                    masked.append(' ')
                    index += 1
                    escape = true
                }

                current == '"' && !escape -> {
                    masked.append(' ')
                    index += 1
                    escape = false
                    exit()
                }

                // `\$` 是转义的字面量美元号，不是模板起点；Java 更是完全没有字符串模板。
                templatesEnabled && !escape && current == '$' && next == '{' -> {
                    masked.append("  ")
                    index += 2
                    templateDepths.addLast(1)
                    enter(LexicalState.TEMPLATE_EXPRESSION)
                }

                current == '\n' -> {
                    masked.append('\n')
                    index += 1
                    escape = false
                    exit()
                }

                else -> {
                    masked.append(' ')
                    index += 1
                    escape = false
                }
            }

            LexicalState.TEMPLATE_EXPRESSION -> when {
                current == '{' -> {
                    masked.append(' ')
                    index += 1
                    // 只加深本层的深度，不碰外层 —— 这正是单一计数器会出错的地方。
                    templateDepths.addLast((templateDepths.removeLastOrNull() ?: 1) + 1)
                }

                current == '}' -> {
                    masked.append(' ')
                    index += 1
                    val remaining = (templateDepths.removeLastOrNull() ?: 1) - 1
                    if (remaining <= 0) exit() else templateDepths.addLast(remaining)
                }

                current == '"' && next == '"' && nextTwo == '"' -> {
                    masked.append("   ")
                    index += 3
                    enter(LexicalState.TRIPLE_QUOTED_STRING)
                }

                current == '"' -> {
                    masked.append(' ')
                    index += 1
                    escape = false
                    enter(LexicalState.STRING)
                }

                current == '\'' -> {
                    masked.append(' ')
                    index += 1
                    escape = false
                    enter(LexicalState.CHAR)
                }

                current == '/' && next == '/' -> {
                    masked.append("  ")
                    index += 2
                    enter(LexicalState.LINE_COMMENT)
                }

                current == '/' && next == '*' -> {
                    masked.append("  ")
                    index += 2
                    blockCommentDepth = 1
                    enter(LexicalState.BLOCK_COMMENT)
                }

                current == '\n' -> {
                    masked.append('\n')
                    index += 1
                }

                else -> {
                    masked.append(' ')
                    index += 1
                }
            }

            LexicalState.CHAR -> when {
                current == '\\' && !escape -> {
                    masked.append(' ')
                    index += 1
                    escape = true
                }

                current == '\'' && !escape -> {
                    masked.append(' ')
                    index += 1
                    escape = false
                    exit()
                }

                current == '\n' -> {
                    masked.append('\n')
                    index += 1
                    escape = false
                    exit()
                }

                else -> {
                    masked.append(' ')
                    index += 1
                    escape = false
                }
            }

            LexicalState.TRIPLE_QUOTED_STRING -> {
                if (current == '"' && next == '"' && nextTwo == '"') {
                    masked.append("   ")
                    index += 3
                    exit()
                } else {
                    masked.append(if (current == '\n') '\n' else ' ')
                    index += 1
                }
            }
        }
    }
    return masked.toString().split('\n')
}


private enum class LexicalState {
    CODE,
    LINE_COMMENT,
    BLOCK_COMMENT,
    STRING,
    CHAR,
    TRIPLE_QUOTED_STRING,

    /** Kotlin 字符串模板 `${...}` 内部；其中的字符串字面量与花括号需单独跟踪。 */
    TEMPLATE_EXPRESSION,
}
