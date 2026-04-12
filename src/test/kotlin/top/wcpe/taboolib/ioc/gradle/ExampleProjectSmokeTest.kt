package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ExampleProjectSmokeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun groovyDslExampleBuildsAndRelocatesAutoInjectedIocLibrary() {
        val result = buildExampleModule(
            moduleName = "groovy-consumer",
            expectedJarPattern = "groovy-consumer-*.jar",
        )

        assertTrue(result.output.contains(":groovy-consumer:build"))
        assertTrue(result.jarEntries.contains(EXPECTED_RELOCATED_IOC_ENTRY))
        assertFalse(result.jarEntries.contains(ORIGINAL_IOC_ENTRY))
    }

    @Test
    fun kotlinDslExampleBuildsAndRelocatesAutoInjectedIocLibrary() {
        val result = buildExampleModule(
            moduleName = "kotlin-consumer",
            expectedJarPattern = "kotlin-consumer-*.jar",
        )

        assertTrue(result.output.contains(":kotlin-consumer:build"))
        assertTrue(result.jarEntries.contains(EXPECTED_RELOCATED_IOC_ENTRY))
        assertFalse(result.jarEntries.contains(ORIGINAL_IOC_ENTRY))
    }

    private fun copyExampleProject(sourceDir: Path, targetDir: Path) {
        targetDir.createDirectories()
        Files.walk(sourceDir).use { paths ->
            paths.forEach { sourcePath ->
                val relative = sourceDir.relativize(sourcePath)
                if (relative.nameCount == 0 || containsGeneratedDirectory(relative)) {
                    return@forEach
                }

                val targetPath = targetDir.resolve(relative.toString())
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath)
                } else {
                    targetPath.parent.createDirectories()
                    Files.copy(sourcePath, targetPath, REPLACE_EXISTING)
                }
            }
        }
    }

    private fun containsGeneratedDirectory(path: Path): Boolean {
        return (0 until path.nameCount).any { path.getName(it).toString() in GENERATED_DIRECTORY_NAMES }
    }

    private fun buildExampleModule(moduleName: String, expectedJarPattern: String): ExampleBuildResult {
        val repoRoot = Path.of("").toAbsolutePath().normalize()
        val projectDir = tempDir.resolve(moduleName)
        val localMavenRepo = projectDir.resolve(".m2-local")
        val pluginVersion = currentPluginVersion(repoRoot)

        copyExampleProject(repoRoot.resolve("example"), projectDir)
        prepareCopiedExampleProject(projectDir, moduleName, pluginVersion)
        publishPluginToLocalRepo(repoRoot, localMavenRepo)

        val buildOutput = runGradle(
            repoRoot = repoRoot,
            projectDir = projectDir,
            arguments = arrayOf(
                "-Dmaven.repo.local=${normalizePath(localMavenRepo)}",
                ":$moduleName:build",
                "--refresh-dependencies",
                "--stacktrace",
            ),
        )

        val jarEntries = JarFile(
            findModuleMainJar(projectDir, moduleName, expectedJarPattern).toFile(),
        ).use { jar ->
            jar.entries().asSequence().map { it.name }.toSet()
        }
        return ExampleBuildResult(buildOutput, jarEntries)
    }

    private fun publishPluginToLocalRepo(repoRoot: Path, localMavenRepo: Path) {
        runGradle(
            repoRoot = repoRoot,
            arguments = arrayOf(
                "-Dmaven.repo.local=${normalizePath(localMavenRepo)}",
                "publishToMavenLocal",
                "--stacktrace",
            ),
        )
    }

    private fun runGradle(repoRoot: Path, projectDir: Path? = null, arguments: Array<String>): String {
        val command = mutableListOf<String>()
        command += gradleCommand(repoRoot)
        command += "--no-daemon"
        command += "-Dkotlin.compiler.execution.strategy=in-process"
        if (projectDir != null) {
            command += "-p"
            command += normalizePath(projectDir)
        }
        command += arguments

        val process = ProcessBuilder(command)
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error(
                buildString {
                    appendLine("Gradle 命令执行失败，exitCode=$exitCode")
                    appendLine(command.joinToString(separator = " "))
                    appendLine()
                    append(output)
                },
            )
        }
        return output
    }

    private fun gradleCommand(repoRoot: Path): String {
        return if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            repoRoot.resolve("gradlew.bat").toString()
        } else {
            repoRoot.resolve("gradlew").toString()
        }
    }

    private fun prepareCopiedExampleProject(projectDir: Path, moduleName: String, pluginVersion: String) {
        val settingsFile = projectDir.resolve("settings.gradle.kts")
        settingsFile.writeText(
            alignPluginVersion(
                settingsFile.readText().replace(
                    "include(\"groovy-consumer\", \"kotlin-consumer\")",
                    "include(\"$moduleName\")",
                ),
                pluginVersion,
            ),
        )

        rewriteIfExists(projectDir.resolve("groovy-consumer/build.gradle")) { content ->
            alignPluginVersion(content, pluginVersion)
        }
        rewriteIfExists(projectDir.resolve("kotlin-consumer/build.gradle.kts")) { content ->
            ensureKotlinJvmPluginVersion(
                alignPluginVersion(content, pluginVersion),
            )
        }
        ensureGradleProperty(projectDir.resolve("gradle.properties"), "kotlin.compiler.execution.strategy", "in-process")
    }

    private fun rewriteIfExists(path: Path, transform: (String) -> String) {
        if (!Files.exists(path)) {
            return
        }
        path.writeText(transform(path.readText()))
    }

    private fun ensureGradleProperty(path: Path, key: String, value: String) {
        val existingLines = if (Files.exists(path)) {
            path.readText().lineSequence().filter { it.isNotBlank() }.toMutableList()
        } else {
            mutableListOf()
        }
        existingLines.removeAll { it.substringBefore('=') == key }
        existingLines.add("$key=$value")
        path.writeText(existingLines.joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator()))
    }

    private fun alignPluginVersion(content: String, pluginVersion: String): String {
        return content
            .replace(
                Regex("""id\("top\\.wcpe\\.taboolib\\.ioc"\) version "[^"]+"""),
                "id(\"top.wcpe.taboolib.ioc\") version \"$pluginVersion\"",
            )
            .replace(
                Regex("""id 'top\\.wcpe\\.taboolib\\.ioc' version '[^']+'"""),
                """id 'top.wcpe.taboolib.ioc' version '$pluginVersion'""",
            )
    }

    private fun ensureKotlinJvmPluginVersion(content: String): String {
        if (content.contains("kotlin(\"jvm\") version")) {
            return content
        }
        return content.replace(
            "    kotlin(\"jvm\")",
            "    kotlin(\"jvm\") version \"1.9.25\"",
        )
    }

    private fun currentPluginVersion(repoRoot: Path): String {
        return Properties().apply {
            Files.newInputStream(repoRoot.resolve("gradle.properties")).use(::load)
        }.getProperty("version")?.trim().orEmpty().ifEmpty {
            error("无法从 gradle.properties 解析当前插件版本")
        }
    }

    private fun normalizePath(path: Path): String {
        return path.toAbsolutePath().normalize().toString().replace("\\", "/")
    }

    private fun findModuleMainJar(projectDir: Path, moduleName: String, jarPattern: String): Path {
        return projectDir.resolve("$moduleName/build/libs")
            .listDirectoryEntries(jarPattern)
            .single { jarPath ->
                val jarName = jarPath.fileName.toString()
                !jarName.endsWith("-sources.jar") &&
                    !jarName.endsWith("-javadoc.jar") &&
                    !jarName.endsWith("-plain.jar")
            }
    }

    private data class ExampleBuildResult(
        val output: String,
        val jarEntries: Set<String>,
    )

    private companion object {
        val GENERATED_DIRECTORY_NAMES = setOf(".gradle", ".idea", "build")
        const val EXPECTED_RELOCATED_IOC_ENTRY = "top/wcpe/mc/plugin/taboolib/ioc/example/ioc/annotation/Component.class"
        const val ORIGINAL_IOC_ENTRY = "top/wcpe/taboolib/ioc/annotation/Component.class"
    }
}
