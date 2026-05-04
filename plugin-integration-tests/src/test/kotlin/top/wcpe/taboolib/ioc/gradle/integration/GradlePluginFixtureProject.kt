package top.wcpe.taboolib.ioc.gradle.integration

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner

internal class GradlePluginFixtureProject(private val rootDir: Path) {

    fun copyFixture(name: String): GradlePluginFixtureProject {
        val fixtureRoot = Path.of(System.getProperty("fixtureDir"))
        val source = fixtureRoot.resolve(name)
        check(source.exists()) { "Fixture not found: $source" }
        copyDirectory(source, rootDir)
        return this
    }

    fun patchBuildScript(oldText: String, newText: String): GradlePluginFixtureProject {
        val buildFile = rootDir.resolve("build.gradle")
        val content = buildFile.readText()
        check(content.contains(oldText)) { "build.gradle does not contain expected text: $oldText" }
        buildFile.writeText(content.replace(oldText, newText))
        return this
    }

    fun build(vararg arguments: String, expectFailure: Boolean = false): BuildResult {
        val runner = GradleRunner.create()
            .withProjectDir(rootDir.toFile())
            .withArguments(arguments.toList() + "--stacktrace")
            .withPluginClasspath()
        return if (expectFailure) runner.buildAndFail() else runner.build()
    }

    fun readStaticDiagnosisReport(): String =
        rootDir.resolve("build/reports/taboolib-ioc/static-diagnosis.json").readText()

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { sourcePath ->
                val targetPath = target.resolve(source.relativize(sourcePath).toString())
                if (Files.isDirectory(sourcePath)) {
                    targetPath.createDirectories()
                } else {
                    targetPath.parent?.createDirectories()
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}

internal data class DiagnosticRecord(
    val severity: String,
    val rule: String,
    val ownerClassName: String,
    val declarationName: String,
)

internal fun String.diagnostics(): List<DiagnosticRecord> {
    val regex = Regex(
        """\{\s*"severity": "([^"]+)",\s*"rule": "([^"]+)",\s*"ownerClassName": "([^"]+)",\s*"declarationName": "([^"]+)""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
    return regex.findAll(this).map { match ->
        DiagnosticRecord(
            severity = match.groupValues[1],
            rule = match.groupValues[2],
            ownerClassName = match.groupValues[3],
            declarationName = match.groupValues[4],
        )
    }.toList()
}
