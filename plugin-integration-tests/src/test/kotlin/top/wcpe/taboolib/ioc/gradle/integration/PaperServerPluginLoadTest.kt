package top.wcpe.taboolib.ioc.gradle.integration

import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

class PaperServerPluginLoadTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun loadsPluginOnRealPaperServerAndVerifiesIocInjection() {
        val projectDir = tempDir.resolve("paper-server-plugin")
        GradlePluginFixtureProject(projectDir).copyFixture("server-plugin")

        // 判定真源从「stdout 文本含 [IoC-Server-Test] PASS」改为「mc-testkit 结果文件 status=PASS」：
        // - 旧链路跑 run-paper 的 :runServer，断言输出文本，窄窗口「先打印 PASS 再崩溃」仍会判绿（假绿）；
        // - 新链路跑 :e2eSmoke，mc-testkit 的 verify 在结果文件 status≠PASS（或缺失）时抛 GradleException
        //   → 构建非零退出 → GradleRunner.build() 抛 UnexpectedBuildFailure。
        //
        // 关键（B-P1-07）：**不吞** UnexpectedBuildFailure。
        // 旧版本 catch 掉构建失败后仅断言输出含 "PASS"，于是构建非零退出仍会判绿（假绿）。
        // 现在构建任务必须真正成功 —— GradleRunner.build() 在 :e2eSmoke 非零退出时抛
        // UnexpectedBuildFailure，直接让本测试失败；只有构建成功才继续做结果文件断言。
        //
        // 注意：不再需要注入 MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR —— fixture 的 build.gradle 已用
        // 双轨写法在配置期自解析 taboolibMainTask 产物绝对路径作为 pluginUnderTest 默认值。
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments(":e2eSmoke", "--stacktrace")
            .withPluginClasspath()
            .build()

        // 强化断言：直接读结果文件 <fixture>/build/mc-testkit/results/smoke.properties，
        // 断言 status=PASS，并把 message 带进断言失败信息。
        val resultFile = projectDir.resolve("build/mc-testkit/results/smoke.properties")
        val properties = Properties().apply {
            resultFile.toFile().reader(Charsets.UTF_8).use { load(it) }
        }
        assertEquals(
            "PASS",
            properties.getProperty("status"),
            "mc-testkit 结果文件未判 PASS：status=${properties.getProperty("status")}, " +
                "message=${properties.getProperty("message")}",
        )
    }
}
