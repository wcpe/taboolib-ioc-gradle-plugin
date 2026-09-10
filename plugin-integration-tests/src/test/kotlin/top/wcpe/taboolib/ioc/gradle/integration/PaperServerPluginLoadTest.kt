package top.wcpe.taboolib.ioc.gradle.integration

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

class PaperServerPluginLoadTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun loadsPluginOnRealPaperServerAndVerifiesIocInjection() {
        val projectDir = tempDir.resolve("paper-server-plugin")
        GradlePluginFixtureProject(projectDir).copyFixture("server-plugin")

        // 关键（B-P1-07）：**不吞** UnexpectedBuildFailure。
        // 旧版本 catch 掉构建失败后仅断言输出含 "PASS"，
        // 于是「服务端先打印 PASS、随后崩溃 / 任务以非零退出」这一窄窗口仍会判绿（假绿）。
        // 现在构建任务必须真正成功 —— GradleRunner.build() 在 :runServer 非零退出时抛
        // UnexpectedBuildFailure，直接让本测试失败；只有构建成功才继续做输出断言。
        val result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments(":runServer", "--stacktrace")
            .withPluginClasspath()
            .build()

        val output = result.output

        // 双重门禁：
        // 1) 构建任务本身必须成功（上面 build() 未抛异常即已保证）；
        // 2) 输出必须同时包含 PASS 标记，且不得出现 FAIL 标记。
        // 二者共同排除「打印 PASS 后崩溃」的假绿。
        assertContains(output, "[IoC-Server-Test] PASS")
        assertFalse(output.contains("[IoC-Server-Test] FAIL"), output)
        assertTrue(
            output.contains("[IoC-Server-Test] PASS"),
            "构建成功不等于注入正确：脚本必须显式输出 [IoC-Server-Test] PASS 标记",
        )
    }
}
