package top.wcpe.taboolib.ioc.gradle.analysis

import java.nio.file.Files
import java.nio.file.Path

internal object StaticAnalysisJsonWriter {

    fun write(report: StaticAnalysisReport, outputFile: Path) {
        outputFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(outputFile, serialize(report.toJsonValue()))
    }

    private fun StaticAnalysisReport.toJsonValue(): Map<String, Any> {
        return linkedMapOf(
            "projectPath" to projectPath,
            "beanCount" to beanIndex.size,
            "injectionPointCount" to injectionPointIndex.size,
            "errorCount" to diagnostics.count { it.severity == DiagnosticSeverity.ERROR },
            "warningCount" to diagnostics.count { it.severity == DiagnosticSeverity.WARNING },
            "beanIndex" to beanIndex.map { bean ->
                linkedMapOf(
                    "ownerClassName" to bean.ownerClassName,
                    "declarationName" to bean.declarationName,
                    "beanName" to bean.beanName,
                    "exposedType" to bean.exposedType,
                    "packageName" to bean.packageName,
                    "sourceFile" to bean.sourceFile,
                    "kind" to bean.kind.name.lowercase(),
                    "primary" to bean.primary,
                    "order" to bean.order,
                    "conditionalAnnotations" to bean.conditionalAnnotations,
                )
            },
            "injectionPointIndex" to injectionPointIndex.map { injection ->
                linkedMapOf(
                    "ownerClassName" to injection.ownerClassName,
                    "declarationName" to injection.declarationName,
                    "dependencyType" to injection.dependencyType,
                    "ownerPackage" to injection.ownerPackage,
                    "sourceFile" to injection.sourceFile,
                    "kind" to injection.kind.name.lowercase(),
                    "qualifierName" to injection.qualifierName,
                    "required" to injection.required,
                )
            },
            "componentScans" to componentScans.map { scan ->
                linkedMapOf(
                    "ownerClassName" to scan.ownerClassName,
                    "packageName" to scan.packageName,
                    "sourceFile" to scan.sourceFile,
                    "basePackages" to scan.basePackages,
                )
            },
            "diagnostics" to diagnostics.map { diagnostic ->
                linkedMapOf(
                    "severity" to diagnostic.severity.name.lowercase(),
                    "rule" to diagnostic.rule,
                    "ownerClassName" to diagnostic.ownerClassName,
                    "declarationName" to diagnostic.declarationName,
                    "dependencyType" to diagnostic.dependencyType,
                    "message" to diagnostic.message,
                    "candidateBeans" to diagnostic.candidateBeans,
                )
            },
        )
    }

    private fun serialize(value: Any?, depth: Int = 0): String {
        return when (value) {
            null -> "null"
            is String -> quote(value)
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> serializeObject(value, depth)
            is Iterable<*> -> serializeArray(value, depth)
            else -> quote(value.toString())
        }
    }

    private fun serializeObject(value: Map<*, *>, depth: Int): String {
        if (value.isEmpty()) {
            return "{}"
        }
        val indent = indent(depth)
        val nestedIndent = indent(depth + 1)
        val body = value.entries.joinToString(",\n") { (key, nestedValue) ->
            "$nestedIndent${quote(key.toString())}: ${serialize(nestedValue, depth + 1)}"
        }
        return "{\n$body\n$indent}"
    }

    private fun serializeArray(value: Iterable<*>, depth: Int): String {
        val items = value.toList()
        if (items.isEmpty()) {
            return "[]"
        }
        val indent = indent(depth)
        val nestedIndent = indent(depth + 1)
        val body = items.joinToString(",\n") { item -> "$nestedIndent${serialize(item, depth + 1)}" }
        return "[\n$body\n$indent]"
    }

    private fun quote(value: String): String {
        return buildString {
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
    }

    private fun indent(depth: Int): String = "  ".repeat(depth)
}