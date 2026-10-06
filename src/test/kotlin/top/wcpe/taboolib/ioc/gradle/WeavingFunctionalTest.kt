package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 织入链路的端到端覆盖：**在真实的 Gradle 构建里开一次 `weaving`，并断言产物真的被织入了**。
 *
 * 补这条之前的缺口是：CI 跑 `build ciTest` 加两个 example 构建，而 example 都没开 `weaving`
 * （默认 false），唯一涉及织入的端到端用例只跑诊断任务、且用 `project.tasks.create` 绕过了插件接线。
 * 也就是说「本批新增的织入能力」在 CI 里一次都没真正执行过 —— 测试全绿不构成任何保证。
 *
 * 这一点在把「织入失败」从 warn 改成中止构建之后尤其要紧：接线若有问题，用户构建会直接挂。
 */
class WeavingFunctionalTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun weavingProducesWovenArtifactEndToEnd() {
        val project = FunctionalTestProject(tempDir).writeFixture(FixtureOptions(weaving = true))

        project.build(":consumer:jar")

        // 合成方法名只存在于常量池，所以断言的是 class 字节而不是 jar 条目名。
        assertTrue(
            project.consumerJarClassBytesContain("\$ioc\$original"),
            "开启 weaving 后产物里必须出现 xxx\$ioc\$original 合成方法，否则说明接线从未真正执行",
        )
        assertTrue(
            project.consumerJarClassBytesContain("WovenTarget"),
            "织入后的类必须带上 WovenTarget 标记接口",
        )
    }

    @Test
    fun weavingDisabledLeavesArtifactUntouched() {
        // 对照：默认（weaving=false）不得改动字节码，否则「默认关闭」这个承诺就是假的。
        val project = FunctionalTestProject(tempDir.resolve("off")).writeFixture(FixtureOptions())

        project.build(":consumer:jar")

        assertTrue(
            !project.consumerJarClassBytesContain("\$ioc\$original"),
            "weaving 默认关闭时产物不得被改动",
        )
    }
}
