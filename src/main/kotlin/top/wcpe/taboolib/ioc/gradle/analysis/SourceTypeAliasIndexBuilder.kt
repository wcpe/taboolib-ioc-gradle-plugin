package top.wcpe.taboolib.ioc.gradle.analysis

import java.nio.file.Files
import java.nio.file.Path

internal object SourceTypeAliasIndexBuilder {

    private val packageRegex = Regex("""^\s*package\s+([A-Za-z0-9_.]+)\s*$""", RegexOption.MULTILINE)
    private val typeAliasRegex = Regex("""^\s*typealias\s+([A-Za-z_][A-Za-z0-9_]*)(<[^>]+>)?\s*=\s*(.+?)\s*$""", RegexOption.MULTILINE)

    fun build(sourceDirectories: Iterable<Path>): List<TypeAliasDefinition> {
        return sourceDirectories
            .filter { Files.exists(it) }
            .flatMap { directory ->
                Files.walk(directory).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                        .map { scanFile(it) }
                        .flatMap { it.stream() }
                        .toList()
                }
            }
            .sortedWith(compareBy({ it.packageName }, { it.aliasName }))
    }

    private fun scanFile(file: Path): List<TypeAliasDefinition> {
        val content = Files.readString(file)
        val packageName = packageRegex.find(content)?.groupValues?.getOrNull(1).orEmpty()
        return typeAliasRegex.findAll(content).map { match ->
            TypeAliasDefinition(
                packageName = packageName,
                aliasName = match.groupValues[1],
                targetType = match.groupValues[3].trim(),
                sourceFile = file.fileName.toString(),
            )
        }.toList()
    }
}