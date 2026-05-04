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
        val mergedGradleProperties = linkedMapOf(
            "kotlin.compiler.execution.strategy" to "in-process",
        ).apply {
            putAll(options.gradleProperties)
        }
        writeFile(
            rootDir.resolve("gradle.properties"),
            mergedGradleProperties.entries.joinToString(separator = System.lineSeparator()) { "${it.key}=${it.value}" },
        )

        writeMockTaboolibBuild()
        writeConsumerProject(options)
        if (options.includeIocLibrary) {
            writeIocLibraryProject()
        }
        return this
    }

    fun build(vararg arguments: String, expectFailure: Boolean = false, gradleVersion: String? = null): BuildResult {
        val runnerArguments = buildList {
            addAll(arguments)
            add("--stacktrace")
        }
        var runner = GradleRunner.create()
            .withProjectDir(rootDir.toFile())
            .withArguments(runnerArguments)
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

    fun writeSourceFile(relativePath: String, content: String): FunctionalTestProject {
        writeFile(rootDir.resolve(relativePath), content)
        return this
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

            group = '${TaboolibIocResolver.DEFAULT_IOC_GROUP}'
            version = '${TaboolibIocResolver.DEFAULT_IOC_VERSION}'
            """.trimIndent(),
        )
        writeFile(
            rootDir.resolve("ioc-lib/src/main/java/top/wcpe/taboolib/ioc/SampleService.java"),
            javaSource(
                packageName = "top.wcpe.taboolib.ioc",
                className = "SampleService",
                body = "public String marker() { return \"ioc\"; }",
            ),
        )
    }

    private fun writeMockTaboolibBuild() {
        val baseDir = rootDir.resolve("build-logic/mock-taboolib")
        writeFile(baseDir.resolve("settings.gradle"), "rootProject.name = 'mock-taboolib'")
        writeFile(baseDir.resolve("build.gradle"), mockTaboolibBuildScript())
        writeFile(
            baseDir.resolve("src/main/java/io/izzel/taboolib/mock/MockTabooLibPlugin.java"),
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
            appendLine("    id 'top.wcpe.taboolib.ioc'")
            appendLine("}")
            if (options.consumerGroup != null) {
                appendLine("group = '${options.consumerGroup}'")
            }
            appendLine("version = '1.0.0'")
            appendLine()
            if (options.applyMockTaboolib) {
                appendLine("taboolib {")
                if (options.manualRelocationTarget != null) {
                    appendLine("    relocate 'top.wcpe.taboolib.ioc', '${options.manualRelocationTarget}'")
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
            appendLine("    id(\"top.wcpe.taboolib.ioc\")")
            appendLine("}")
            if (options.consumerGroup != null) {
                appendLine("group = \"${options.consumerGroup}\"")
            }
            appendLine("version = \"1.0.0\"")
            appendLine()
            if (options.applyMockTaboolib) {
                appendLine("taboolib {")
                if (options.manualRelocationTarget != null) {
                    appendLine("    relocate(\"top.wcpe.taboolib.ioc\", \"${options.manualRelocationTarget}\")")
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
                id 'java-gradle-plugin'
            }

            repositories {
                gradlePluginPortal()
                mavenCentral()
            }

            dependencies {
                implementation gradleApi()
            }

            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(17)
                }
            }

            gradlePlugin {
                plugins {
                    mockTaboolib {
                        id = 'io.izzel.taboolib'
                        implementationClass = 'io.izzel.taboolib.mock.MockTabooLibPlugin'
                    }
                }
            }
        """
    }

    private fun mockTaboolibPluginSource(): String {
        return """
            package io.izzel.taboolib.mock;

            import java.io.File;
            import java.io.FileOutputStream;
            import java.io.IOException;
            import java.nio.file.Files;
            import java.nio.file.StandardCopyOption;
            import java.util.LinkedHashMap;
            import java.util.LinkedHashSet;
            import java.util.Map;
            import java.util.jar.JarEntry;
            import java.util.jar.JarFile;
            import java.util.jar.JarOutputStream;
            import org.gradle.api.Action;
            import org.gradle.api.DefaultTask;
            import org.gradle.api.Plugin;
            import org.gradle.api.Project;
            import org.gradle.api.artifacts.Configuration;
            import org.gradle.api.file.DuplicatesStrategy;
            import org.gradle.api.file.RegularFileProperty;
            import org.gradle.api.provider.MapProperty;
            import org.gradle.api.tasks.Input;
            import org.gradle.api.tasks.InputFile;
            import org.gradle.api.tasks.OutputFile;
            import org.gradle.api.tasks.TaskAction;
            import org.gradle.jvm.tasks.Jar;
            import org.gradle.work.DisableCachingByDefault;

            public class MockTabooLibPlugin implements Plugin<Project> {

                public static class MockTaboolibEnv {

                    private String group;

                    public String getGroup() {
                        return group;
                    }

                    public void setGroup(String group) {
                        this.group = group;
                    }
                }

                public static class MockTaboolibExtension {

                    private final Map<String, String> relocation = new LinkedHashMap<>();
                    private final MockTaboolibEnv env = new MockTaboolibEnv();
                    private boolean subproject;

                    public Map<String, String> getRelocation() {
                        return relocation;
                    }

                    public MockTaboolibEnv getEnv() {
                        return env;
                    }

                    public boolean isSubproject() {
                        return subproject;
                    }

                    public void setSubproject(boolean subproject) {
                        this.subproject = subproject;
                    }

                    public void relocate(String prefix, String target) {
                        relocation.put(prefix, target);
                    }

                    public void env(Action<? super MockTaboolibEnv> action) {
                        action.execute(env);
                    }
                }

                @DisableCachingByDefault(because = "Functional test fixture task")
                public abstract static class MockTaboolibMainTask extends DefaultTask {

                    @InputFile
                    public abstract RegularFileProperty getInputJar();

                    @OutputFile
                    public abstract RegularFileProperty getOutputJar();

                    @Input
                    public abstract MapProperty<String, String> getRelocations();

                    @TaskAction
                    public void rewrite() throws IOException {
                        File input = getInputJar().get().getAsFile();
                        File output = getOutputJar().get().getAsFile();
                        File tempFile = File.createTempFile(output.getName().replace('.', '_'), ".jar");

                        try (JarFile sourceJar = new JarFile(input); JarOutputStream targetJar = new JarOutputStream(new FileOutputStream(tempFile))) {
                            LinkedHashSet<String> written = new LinkedHashSet<>();
                            var entries = sourceJar.entries();
                            while (entries.hasMoreElements()) {
                                JarEntry entry = entries.nextElement();
                                String mappedName = mapEntry(entry.getName());
                                if (!written.add(mappedName)) {
                                    continue;
                                }
                                targetJar.putNextEntry(new JarEntry(mappedName));
                                if (!entry.isDirectory()) {
                                    try (var inputStream = sourceJar.getInputStream(entry)) {
                                        inputStream.transferTo(targetJar);
                                    }
                                }
                                targetJar.closeEntry();
                            }
                        }

                        Files.copy(tempFile.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        tempFile.delete();
                    }

                    private String mapEntry(String path) {
                        String mapped = path;
                        for (Map.Entry<String, String> relocation : getRelocations().get().entrySet()) {
                            String fromPath = relocation.getKey().replace('.', '/');
                            String toPath = relocation.getValue().replace('.', '/');
                            if (mapped.startsWith(fromPath)) {
                                mapped = toPath + mapped.substring(fromPath.length());
                            }
                        }
                        return mapped;
                    }
                }

                @Override
                public void apply(Project project) {
                    MockTaboolibExtension extension = project.getExtensions().create("taboolib", MockTaboolibExtension.class);
                    Configuration taboo = project.getConfigurations().maybeCreate("taboo");
                    project.getConfigurations().maybeCreate("include");
                    var mainTask = project.getTasks().register("taboolibMainTask", MockTaboolibMainTask.class, task -> task.setGroup("taboolib"));

                    project.afterEvaluate(ignored -> {
                        var packagedDependencies = taboo.getFiles();
                        var jarTask = project.getTasks().named("jar", Jar.class);
                        jarTask.configure(jar -> {
                            jar.from(packagedDependencies.stream().map(dependency -> dependency.isDirectory() ? dependency : project.zipTree(dependency)).toList());
                            jar.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
                            jar.finalizedBy(mainTask);
                        });
                        mainTask.configure(task -> {
                            task.getInputJar().set(jarTask.flatMap(jar -> jar.getArchiveFile()));
                            task.getOutputJar().set(jarTask.flatMap(jar -> jar.getArchiveFile()));
                            task.getRelocations().set(extension.getRelocation());
                        });
                    });
                }
            }
        """
    }
}