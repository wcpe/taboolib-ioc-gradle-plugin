package top.wcpe.taboolib.ioc.gradle.analysis

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Generates a static diagnosis report from compiled classes")
abstract class AnalyzeTaboolibIocBeansTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dependencyArtifacts: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectories: ConfigurableFileCollection

    @get:Input
    abstract val failOnError: Property<Boolean>

    @get:Input
    abstract val failOnWarning: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val projectPropertiesInput: MapProperty<String, String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun generateReport() {
        val scanRoots = (classDirectories.files + dependencyArtifacts.files)
            .filter { it.exists() }
            .map { it.toPath() }
        val typeAliases = SourceTypeAliasIndexBuilder.build(sourceDirectories.files.map { it.toPath() })
        val index = BytecodeBeanIndexBuilder.build(scanRoots)
        val report = StaticDiagnosisEngine.analyze(
            projectPath = project.path,
            index = index,
            typeAliases = typeAliases,
            projectProperties = projectPropertiesInput.getOrElse(emptyMap()),
        )
        val outputFile = reportFile.get().asFile.toPath()
        StaticAnalysisJsonWriter.write(report, outputFile)

        val errorCount = report.diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
        val warningCount = report.diagnostics.count { it.severity == DiagnosticSeverity.WARNING }

        logger.lifecycle(
            "[analyzeTaboolibIocBeans] beans=${report.beanIndex.size}, injections=${report.injectionPointIndex.size}, " +
                "errors=$errorCount, warnings=$warningCount",
        )
        logger.lifecycle("[analyzeTaboolibIocBeans] report=${outputFile}")

        if (failOnWarning.get() && warningCount > 0) {
            throw GradleException("静态诊断发现 $warningCount 个 warning，failOnWarning=true，已中断构建。")
        }
        if (failOnError.get() && errorCount > 0) {
            throw GradleException("静态诊断发现 $errorCount 个 error，failOnError=true，已中断构建。")
        }
    }
}