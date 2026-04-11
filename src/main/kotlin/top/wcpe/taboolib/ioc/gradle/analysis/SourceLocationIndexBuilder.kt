package top.wcpe.taboolib.ioc.gradle.analysis

import java.nio.file.Files
import java.nio.file.Path

internal object SourceLocationIndexBuilder {

    private val packageRegex = Regex("""^\s*package\s+([A-Za-z0-9_.]+)\s*;?\s*$""")
    private val topLevelTypeRegex = Regex("""\b(class|interface|enum|object)\s+([A-Za-z_][A-Za-z0-9_]*)\b""")

    fun build(sourceDirectories: Iterable<Path>): SourceLocationIndex {
        val classEntries = linkedMapOf<String, SourceClassEntry>()
        sourceDirectories
            .filter { Files.exists(it) }
            .distinct()
            .forEach { directory ->
                Files.walk(directory).use { stream ->
                    stream.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.toString().endsWith(".kt")) }
                        .forEach { file ->
                            scanFile(file).forEach { entry ->
                                classEntries.putIfAbsent(entry.className, entry)
                            }
                        }
                }
            }
        return SourceLocationIndex(classEntries)
    }

    private fun scanFile(file: Path): List<SourceClassEntry> {
        val lines = Files.readAllLines(file)
        val packageName = lines.firstNotNullOfOrNull { line ->
            packageRegex.matchEntire(line)?.groupValues?.getOrNull(1)
        }.orEmpty()
        val matches = lines.mapIndexedNotNull { index, line ->
            val match = topLevelTypeRegex.find(line) ?: return@mapIndexedNotNull null
            val simpleName = match.groupValues[2]
            IndexedClassMatch(simpleName = simpleName, line = index + 1)
        }
        if (matches.isEmpty()) {
            return emptyList()
        }
        return matches.mapIndexed { index, match ->
            val endLine = matches.getOrNull(index + 1)?.line?.minus(1) ?: lines.size
            val className = if (packageName.isBlank()) match.simpleName else "$packageName.${match.simpleName}"
            SourceClassEntry(
                className = className,
                simpleName = match.simpleName,
                filePath = file.toAbsolutePath().normalize(),
                lines = lines,
                startLine = match.line,
                endLine = endLine,
            )
        }
    }

    private data class IndexedClassMatch(
        val simpleName: String,
        val line: Int,
    )
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
) {

    fun resolve(injectionPoint: InjectionPointDefinition): SourceLocation? {
        val classEntry = classEntries[injectionPoint.ownerClassName] ?: return null
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
) {

    private val maskedLines: List<String> by lazy { maskNonCodeSegments(lines) }

    fun resolve(injectionPoint: InjectionPointDefinition): SourceLocation {
        val pattern = when (injectionPoint.kind) {
            InjectionPointKind.CONSTRUCTOR_PARAMETER -> Regex("""\b${Regex.escape(simpleName)}\s*\(""")
            InjectionPointKind.METHOD_PARAMETER -> Regex("""\b${Regex.escape(injectionPoint.declarationName)}\s*\(""")
            InjectionPointKind.FIELD -> Regex("""\b${Regex.escape(injectionPoint.declarationName)}\b(?=\s*[:;=])""")
        }
        val fallbackColumn = maskedLines.getOrNull(startLine - 1)
            ?.indexOf(simpleName)
            ?.takeIf { it >= 0 }
            ?.plus(1)
            ?: 1
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
        return SourceLocation(
            sourcePath = filePath.toString(),
            sourceLine = startLine,
            sourceColumn = fallbackColumn,
        )
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
        val assignmentRegex = Regex(
            """(?s)(?<![A-Za-z0-9_])(?:this\.)?${Regex.escape(fieldName)}\s*(?:[+\-*/%&|^]?=)(?![=])""",
        )
        return assignmentRegex.containsMatchIn(codeAfterDeclaration)
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
        val fieldMatch = Regex("""\b${Regex.escape(fieldName)}\b""").find(this) ?: return null
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

    private fun String.containsDelegateKeyword(): Boolean {
        return Regex("""\bby\b""").containsMatchIn(this)
    }

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

    private fun maskNonCodeSegments(sourceLines: List<String>): List<String> {
        val source = sourceLines.joinToString("\n")
        val masked = StringBuilder(source.length)
        var index = 0
        var state = LexicalState.CODE
        var escape = false
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            val nextTwo = source.getOrNull(index + 2)
            when (state) {
                LexicalState.CODE -> when {
                    current == '/' && next == '/' -> {
                        masked.append("  ")
                        index += 2
                        state = LexicalState.LINE_COMMENT
                    }

                    current == '/' && next == '*' -> {
                        masked.append("  ")
                        index += 2
                        state = LexicalState.BLOCK_COMMENT
                    }

                    current == '"' && next == '"' && nextTwo == '"' -> {
                        masked.append("   ")
                        index += 3
                        state = LexicalState.TRIPLE_QUOTED_STRING
                    }

                    current == '"' -> {
                        masked.append(' ')
                        index += 1
                        state = LexicalState.STRING
                        escape = false
                    }

                    current == '\'' -> {
                        masked.append(' ')
                        index += 1
                        state = LexicalState.CHAR
                        escape = false
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
                        state = LexicalState.CODE
                    } else {
                        masked.append(' ')
                        index += 1
                    }
                }

                LexicalState.BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        masked.append("  ")
                        index += 2
                        state = LexicalState.CODE
                    } else {
                        masked.append(if (current == '\n') '\n' else ' ')
                        index += 1
                    }
                }

                LexicalState.STRING -> {
                    if (current == '\n') {
                        masked.append('\n')
                        index += 1
                        state = LexicalState.CODE
                        escape = false
                    } else {
                        masked.append(' ')
                        val closes = current == '"' && !escape
                        escape = current == '\\' && !escape
                        index += 1
                        if (closes) {
                            state = LexicalState.CODE
                            escape = false
                        }
                    }
                }

                LexicalState.CHAR -> {
                    if (current == '\n') {
                        masked.append('\n')
                        index += 1
                        state = LexicalState.CODE
                        escape = false
                    } else {
                        masked.append(' ')
                        val closes = current == '\'' && !escape
                        escape = current == '\\' && !escape
                        index += 1
                        if (closes) {
                            state = LexicalState.CODE
                            escape = false
                        }
                    }
                }

                LexicalState.TRIPLE_QUOTED_STRING -> {
                    if (current == '"' && next == '"' && nextTwo == '"') {
                        masked.append("   ")
                        index += 3
                        state = LexicalState.CODE
                    } else {
                        masked.append(if (current == '\n') '\n' else ' ')
                        index += 1
                    }
                }
            }
        }
        return masked.toString().split('\n')
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

    private enum class LexicalState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHAR,
        TRIPLE_QUOTED_STRING,
    }

    private companion object {
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
