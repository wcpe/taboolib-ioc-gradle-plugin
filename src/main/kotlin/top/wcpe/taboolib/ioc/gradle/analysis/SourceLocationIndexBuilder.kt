package top.wcpe.taboolib.ioc.gradle.analysis

import java.nio.file.Files
import java.nio.file.Path

internal object SourceLocationIndexBuilder {

    private val packageRegex = Regex("""^\s*package\s+([A-Za-z0-9_.]+)\s*$""")
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

internal class SourceLocationIndex(
    private val classEntries: Map<String, SourceClassEntry>,
) {

    fun resolve(injectionPoint: InjectionPointDefinition): SourceLocation? {
        val classEntry = classEntries[injectionPoint.ownerClassName] ?: return null
        return classEntry.resolve(injectionPoint)
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

    fun resolve(injectionPoint: InjectionPointDefinition): SourceLocation {
        val pattern = when (injectionPoint.kind) {
            InjectionPointKind.CONSTRUCTOR_PARAMETER -> Regex("""\b${Regex.escape(simpleName)}\s*\(""")
            InjectionPointKind.METHOD_PARAMETER -> Regex("""\b${Regex.escape(injectionPoint.declarationName)}\s*\(""")
            InjectionPointKind.FIELD -> Regex("""\b${Regex.escape(injectionPoint.declarationName)}\b(?=\s*[:;=])""")
        }
        val fallbackColumn = lines.getOrNull(startLine - 1)
            ?.indexOf(simpleName)
            ?.takeIf { it >= 0 }
            ?.plus(1)
            ?: 1
        for (lineNumber in startLine..endLine) {
            val line = lines.getOrNull(lineNumber - 1) ?: continue
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
}