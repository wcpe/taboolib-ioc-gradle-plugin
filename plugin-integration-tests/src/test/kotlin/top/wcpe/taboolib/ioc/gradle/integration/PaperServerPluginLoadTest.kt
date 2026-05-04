package top.wcpe.taboolib.ioc.gradle.integration

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.io.TempDir

class PaperServerPluginLoadTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun loadsPluginOnRealPaperServerAndVerifiesIocInjection() {
        val projectDir = tempDir.resolve("paper-server-plugin")
        GradlePluginFixtureProject(projectDir).copyFixture("server-plugin")

        val output = try {
            GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(":runServer", "--stacktrace")
                .withPluginClasspath()
                .build()
                .output
        } catch (failure: UnexpectedBuildFailure) {
            failure.buildResult.output
        }

        assertContains(output, "[IoC-Server-Test] PASS")
        assertFalse(output.contains("[IoC-Server-Test] FAIL"), output)
    }

}
