package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

class ExampleProjectSmokeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun groovyDslExampleBuildsWithoutLocalProjectDependencyAndRelocatesPublishedIocLibrary() {
        val result = buildExampleModule(
            moduleName = "groovy-consumer",
            expectedJarPattern = "groovy-consumer-*.jar",
        )

        assertTrue(result.output.contains(":groovy-consumer:build"))
        assertTrue(result.jarEntries.contains(GROOVY_EXPECTED_RELOCATED_IOC_ENTRY))
        assertFalse(result.jarEntries.contains("top/wcpe/taboolib/ioc/ExampleIocService.class"))
    }

    @Test
    fun kotlinDslExampleBuildsWithoutLocalProjectDependencyAndRelocatesPublishedIocLibrary() {
        val result = buildExampleModule(
            moduleName = "kotlin-consumer",
            expectedJarPattern = "kotlin-consumer-*.jar",
        )

        assertTrue(result.output.contains(":kotlin-consumer:build"))
        assertTrue(result.jarEntries.contains(KOTLIN_EXPECTED_RELOCATED_IOC_ENTRY))
        assertFalse(result.jarEntries.contains("top/wcpe/taboolib/ioc/ExampleIocService.class"))
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

        copyExampleProject(repoRoot.resolve("example"), projectDir)

        val settingsFile = projectDir.resolve("settings.gradle")
        settingsFile.writeText(
            settingsFile.readText().replace(
                "includeBuild('..')",
                "includeBuild('${repoRoot.toString().replace("\\", "/")}')",
            ),
        )

        val isolatedMavenRepo = projectDir.resolve(".m2-local").toAbsolutePath().normalize()

        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments(
                ":ioc-lib:publishToMavenLocal",
                "-Pexample.modules=ioc-lib",
                "-Dmaven.repo.local=${isolatedMavenRepo.toString().replace("\\", "/")}",
                "--stacktrace",
            )
            .build()

        val buildResult = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments(
                ":$moduleName:build",
                "-Pexample.modules=$moduleName",
                "-Dmaven.repo.local=${isolatedMavenRepo.toString().replace("\\", "/")}",
                "--refresh-dependencies",
                "--stacktrace",
            )
            .build()

        val jarEntries = JarFile(
            findModuleMainJar(projectDir, moduleName, expectedJarPattern).toFile(),
        ).use { jar ->
            jar.entries().asSequence().map { it.name }.toSet()
        }
        return ExampleBuildResult(buildResult.output, jarEntries)
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
        const val GROOVY_EXPECTED_RELOCATED_IOC_ENTRY = "com/example/groovy/ioc/ExampleIocService.class"
        const val KOTLIN_EXPECTED_RELOCATED_IOC_ENTRY = "com/example/custom/ioc/ExampleIocService.class"
    }
}
