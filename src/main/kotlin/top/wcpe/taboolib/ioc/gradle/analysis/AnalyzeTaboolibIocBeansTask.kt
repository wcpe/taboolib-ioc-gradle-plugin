package top.wcpe.taboolib.ioc.gradle.analysis

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
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

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun generateReport() {
        val existingDirectories = classDirectories.files.filter { it.exists() }.map { it.toPath() }
        val index = BytecodeBeanIndexBuilder.build(existingDirectories)
        val report = StaticDiagnosisEngine.analyze(project.path, index)
        val outputFile = reportFile.get().asFile.toPath()
        StaticAnalysisJsonWriter.write(report, outputFile)

        logger.lifecycle(
            "[analyzeTaboolibIocBeans] beans=${report.beanIndex.size}, injections=${report.injectionPointIndex.size}, " +
                "errors=${report.diagnostics.count { it.severity == DiagnosticSeverity.ERROR }}, " +
                "warnings=${report.diagnostics.count { it.severity == DiagnosticSeverity.WARNING }}",
        )
        logger.lifecycle("[analyzeTaboolibIocBeans] report=${outputFile}")
    }
}