package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner

internal data class FixtureOptions(
    val applyMockTaboolib: Boolean = true,
    val includeIocLibrary: Boolean = true,
    val includeStaticDiagnosisSamples: Boolean = false,
    val useKotlinDslConsumer: Boolean = false,
    val explicitIocVersion: String? = null,
    val analysisFailOnError: Boolean? = null,
    val analysisFailOnWarning: Boolean? = null,
    val explicitTargetPackage: String? = null,
    val rootGroup: String? = "com.example.root",
    val consumerGroup: String? = null,
    val gradleProperties: Map<String, String> = emptyMap(),
    val manualRelocationTarget: String? = null,
    val autoTakeover: Boolean = true,
    val localProjectPath: String? = ":ioc-lib",
    val taboolibSubproject: Boolean = false,
)

internal class FunctionalTestProject(private val rootDir: Path) {

    fun writeFixture(options: FixtureOptions): FunctionalTestProject {
        rootDir.createDirectories()
        writeFile(rootDir.resolve("settings.gradle"), settingsScript(options))
        writeFile(rootDir.resolve("build.gradle"), rootBuildScript(options))
        if (options.gradleProperties.isNotEmpty()) {
            writeFile(
                rootDir.resolve("gradle.properties"),
                options.gradleProperties.entries.joinToString(separator = System.lineSeparator()) { "${it.key}=${it.value}" },
            )
        }

        writeMockTaboolibBuild()
        writeConsumerProject(options)
        if (options.includeIocLibrary) {
            writeIocLibraryProject()
        }
        return this
    }

    fun build(vararg arguments: String, expectFailure: Boolean = false, gradleVersion: String? = null): BuildResult {
        var runner = GradleRunner.create()
            .withProjectDir(rootDir.toFile())
            .withArguments(*arguments, "--stacktrace")
            .withPluginClasspath()
        if (!gradleVersion.isNullOrBlank()) {
            runner = runner.withGradleVersion(gradleVersion)
        }
        return if (expectFailure) {
            runner.buildAndFail()
        } else {
            runner.build()
        }
    }

    fun consumerJarEntries(): Set<String> {
        val jarFile = rootDir.resolve("consumer/build/libs").listDirectoryEntries("*.jar").single()
        JarFile(jarFile.toFile()).use { jar ->
            return jar.entries().asSequence().map { it.name }.toSet()
        }
    }

    fun readRelativeFile(relativePath: String): String {
        return rootDir.resolve(relativePath).readText()
    }

    private fun writeConsumerProject(options: FixtureOptions) {
        val buildScriptName = if (options.useKotlinDslConsumer) "build.gradle.kts" else "build.gradle"
        writeFile(rootDir.resolve("consumer/$buildScriptName"), consumerBuildScript(options))
        writeFile(
            rootDir.resolve("consumer/src/main/java/fixture/consumer/ConsumerEntry.java"),
            javaSource(
                packageName = "fixture.consumer",
                className = "ConsumerEntry",
                body = "public String value() { return \"ok\"; }",
            ),
        )
        if (options.includeStaticDiagnosisSamples) {
            StaticDiagnosisFixtureSources.writeJavaSources(rootDir.resolve("consumer/src/main/java"))
        }
    }

    private fun writeIocLibraryProject() {
        writeFile(
            rootDir.resolve("ioc-lib/build.gradle"),
            """
            plugins {
                id 'java'
            }

            group = 'top.wcpe.taboolib.ioc.properties'
            version = '1.0.0-SNAPSHOT'
            """.trimIndent(),
        )
        writeFile(
            rootDir.resolve("ioc-lib/src/main/java/top/wcpe/taboolib/ioc/SampleService.java"),
            javaSource(
                packageName = "top.wcpe.taboolib.ioc.properties",
                className = "SampleService",
                body = "public String marker() { return \"ioc\"; }",
            ),
        )
    }

    private fun writeMockTaboolibBuild() {
        val baseDir = rootDir.resolve("build-logic/mock-taboolib")
        writeFile(baseDir.resolve("settings.gradle.kts"), "rootProject.name = \"mock-taboolib\"")
        writeFile(baseDir.resolve("build.gradle.kts"), mockTaboolibBuildScript())
        writeFile(
            baseDir.resolve("src/main/kotlin/io/izzel/taboolib/mock/MockTabooLibPlugin.kt"),
            mockTaboolibPluginSource(),
        )
    }

    private fun writeFile(path: Path, content: String) {
        path.parent?.createDirectories()
        path.writeText(content.trimIndent() + System.lineSeparator())
    }

    private fun settingsScript(options: FixtureOptions): String {
        return buildString {
            appendLine("pluginManagement {")
            appendLine("    includeBuild('build-logic/mock-taboolib')")
            appendLine("    repositories {")
            appendLine("        gradlePluginPortal()")
            appendLine("        mavenCentral()")
            appendLine("    }")
            appendLine("}")
            appendLine()
            appendLine("rootProject.name = 'functional-test'")
            appendLine("include 'consumer'")
            if (options.includeIocLibrary) {
                appendLine("include 'ioc-lib'")
            }
        }
    }

    private fun rootBuildScript(options: FixtureOptions): String {
        return buildString {
            appendLine("allprojects {")
            if (options.rootGroup != null) {
                appendLine("    group = '${options.rootGroup}'")
            }
            appendLine("    version = '1.0.0'")
            appendLine("    repositories { mavenCentral() }")
            appendLine("}")
        }
    }

    private fun consumerBuildScript(options: FixtureOptions): String {
        return if (options.useKotlinDslConsumer) {
            consumerKotlinBuildScript(options)
        } else {
            consumerGroovyBuildScript(options)
        }
    }

    private fun consumerGroovyBuildScript(options: FixtureOptions): String {
        return buildString {
            appendLine("plugins {")
            appendLine("    id 'java'")
            if (options.applyMockTaboolib) {
                appendLine("    id 'io.izzel.taboolib'")
            }
            appendLine("    id 'top.wcpe.taboolib.ioc.properties'")
            appendLine("}")
            if (options.consumerGroup != null) {
                appendLine("group = '${options.consumerGroup}'")
            }
            appendLine("version = '1.0.0'")
            appendLine()
            if (options.applyMockTaboolib) {
                appendLine("taboolib {")
                if (options.manualRelocationTarget != null) {
                    appendLine("    relocate 'top.wcpe.taboolib.ioc.properties', '${options.manualRelocationTarget}'")
                }
                if (options.taboolibSubproject) {
                    appendLine("    subproject = true")
                }
                appendLine("}")
                appendLine()
            }
            appendLine("taboolibIoc {")
            appendLine("    autoTakeover = ${options.autoTakeover}")
            options.explicitIocVersion?.let { appendLine("    iocVersion = '$it'") }
            options.analysisFailOnError?.let { appendLine("    analysisFailOnError = $it") }
            options.analysisFailOnWarning?.let { appendLine("    analysisFailOnWarning = $it") }
            if (options.explicitTargetPackage != null) {
                appendLine("    targetPackage = '${options.explicitTargetPackage}'")
            }
            if (options.localProjectPath != null && options.includeIocLibrary) {
                appendLine("    useLocalProject '${options.localProjectPath}'")
            }
            appendLine("}")
        }
    }

    private fun consumerKotlinBuildScript(options: FixtureOptions): String {
        return buildString {
            appendLine("plugins {")
            appendLine("    java")
            if (options.applyMockTaboolib) {
                appendLine("    id(\"io.izzel.taboolib\")")
            }
            appendLine("    id(\"top.wcpe.taboolib.ioc.properties\")")
            appendLine("}")
            if (options.consumerGroup != null) {
                appendLine("group = \"${options.consumerGroup}\"")
            }
            appendLine("version = \"1.0.0\"")
            appendLine()
            if (options.applyMockTaboolib) {
                appendLine("taboolib {")
                if (options.manualRelocationTarget != null) {
                    appendLine("    relocate(\"top.wcpe.taboolib.ioc.properties\", \"${options.manualRelocationTarget}\")")
                }
                if (options.taboolibSubproject) {
                    appendLine("    subproject = true")
                }
                appendLine("}")
                appendLine()
            }
            appendLine("taboolibIoc {")
            appendLine("    autoTakeover(${options.autoTakeover})")
            options.explicitIocVersion?.let { appendLine("    iocVersion(\"$it\")") }
            options.analysisFailOnError?.let { appendLine("    analysisFailOnError($it)") }
            options.analysisFailOnWarning?.let { appendLine("    analysisFailOnWarning($it)") }
            options.explicitTargetPackage?.let { appendLine("    targetPackage(\"$it\")") }
            if (options.localProjectPath != null && options.includeIocLibrary) {
                appendLine("    useLocalProject(\"${options.localProjectPath}\")")
            }
            appendLine("}")
        }
    }

    private fun javaSource(packageName: String, className: String, body: String): String {
        return """
            package $packageName;

            public class $className {
                $body
            }
        """
    }

    private fun mockTaboolibBuildScript(): String {
        return """
            plugins {
                kotlin("jvm") version "1.9.25"
                `java-gradle-plugin`
            }

            repositories {
                gradlePluginPortal()
                mavenCentral()
            }

            dependencies {
                implementation(kotlin("stdlib"))
            }

            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(17))
                }
            }

            gradlePlugin {
                plugins {
                    create("mockTaboolib") {
                        id = "io.izzel.taboolib"
                        implementationClass = "io.izzel.taboolib.mock.MockTabooLibPlugin"
                    }
                }
            }
        """
    }

    private fun mockTaboolibPluginSource(): String {
        return """
            package io.izzel.taboolib.mock

            import java.io.FileOutputStream
            import java.util.jar.JarEntry
            import java.util.jar.JarFile
            import java.util.jar.JarOutputStream
            import org.gradle.api.Action
            import org.gradle.api.DefaultTask
            import org.gradle.api.Plugin
            import org.gradle.api.Project
            import org.gradle.api.file.DuplicatesStrategy
            import org.gradle.api.provider.MapProperty
            import org.gradle.api.tasks.Input
            import org.gradle.api.tasks.InputFile
            import org.gradle.api.tasks.Optional
            import org.gradle.api.tasks.OutputFile
            import org.gradle.api.tasks.TaskAction
            import org.gradle.jvm.tasks.Jar
            import org.gradle.work.DisableCachingByDefault

            open class MockTaboolibEnv {
                var group: String? = null
            }

            open class MockTaboolibExtension {
                val relocation: MutableMap<String, String> = linkedMapOf()
                val env: MockTaboolibEnv = MockTaboolibEnv()
                var subproject: Boolean = false

                fun relocate(prefix: String, target: String) {
                    relocation[prefix] = target
                }

                fun env(action: Action<MockTaboolibEnv>) {
                    action.execute(env)
                }
            }

            @DisableCachingByDefault(because = "Functional test fixture task")
            abstract class MockTaboolibMainTask : DefaultTask() {

                @get:InputFile
                abstract val inputJar: org.gradle.api.file.RegularFileProperty

                @get:OutputFile
                abstract val outputJar: org.gradle.api.file.RegularFileProperty

                @get:Input
                abstract val relocations: MapProperty<String, String>

                @TaskAction
                fun rewrite() {
                    val input = inputJar.get().asFile
                    val output = outputJar.get().asFile
                    val tempFile = kotlin.io.path.createTempFile(prefix = output.nameWithoutExtension, suffix = ".jar").toFile()

                    JarFile(input).use { sourceJar ->
                        JarOutputStream(FileOutputStream(tempFile)).use { targetJar ->
                            val written = linkedSetOf<String>()
                            sourceJar.entries().asSequence().forEach { entry ->
                                val mappedName = mapEntry(entry.name)
                                if (written.add(mappedName)) {
                                    targetJar.putNextEntry(JarEntry(mappedName))
                                    if (!entry.isDirectory) {
                                        sourceJar.getInputStream(entry).use { inputStream ->
                                            inputStream.copyTo(targetJar)
                                        }
                                    }
                                    targetJar.closeEntry()
                                }
                            }
                        }
                    }

                    tempFile.copyTo(output, overwrite = true)
                    tempFile.delete()
                }

                private fun mapEntry(path: String): String {
                    var mapped = path
                    relocations.get().entries.forEach { (from, to) ->
                        val fromPath = from.replace('.', '/')
                        val toPath = to.replace('.', '/')
                        if (mapped.startsWith(fromPath)) {
                            mapped = toPath + mapped.removePrefix(fromPath)
                        }
                    }
                    return mapped
                }
            }

            class MockTabooLibPlugin : Plugin<Project> {

                override fun apply(project: Project) {
                    val extension = project.extensions.create("taboolib", MockTaboolibExtension::class.java)
                    val taboo = project.configurations.maybeCreate("taboo")
                    project.configurations.maybeCreate("include")
                    val mainTask = project.tasks.register("taboolibMainTask", MockTaboolibMainTask::class.java) {
                        it.group = "taboolib"
                    }

                    project.afterEvaluate {
                        val packagedDependencies = taboo.files
                        val jarTask = project.tasks.named("jar", Jar::class.java)
                        jarTask.configure { jar ->
                            jar.from(packagedDependencies.map { dependency ->
                                if (dependency.isDirectory) {
                                    dependency
                                } else {
                                    project.zipTree(dependency)
                                }
                            })
                            jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                            jar.finalizedBy(mainTask)
                        }

                        mainTask.configure { task ->
                            task.inputJar.set(jarTask.flatMap { it.archiveFile })
                            task.outputJar.set(jarTask.flatMap { it.archiveFile })
                            task.relocations.set(extension.relocation.toMap())
                        }
                    }
                }
            }
        """
    }
}