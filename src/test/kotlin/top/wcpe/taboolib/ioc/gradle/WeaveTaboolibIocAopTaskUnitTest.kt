package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import top.wcpe.taboolib.ioc.gradle.weaving.WeaveTaboolibIocAopTask

/**
 * 织入任务的行为约束：**失败必须中止构建**。
 *
 * 诊断侧的 `aop-target-not-proxied` 抑制只依据织入计划，而计划由 `planTaboolibIocAop` 在织入
 * **之前**写出。织入失败若只记 warn，失败的类在计划里仍然是 `WOVEN`，抑制照旧生效、报告一片干净，
 * 用户拿到的是「切面静默不执行且毫无提示」的产物 —— 这类「构建成功 + 报告干净 + 运行期不生效」
 * 比直接失败危险得多，因此宁可打断构建。
 */
class WeaveTaboolibIocAopTaskUnitTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun failsBuildWhenAnyClassCannotBeWoven() {
        val classesDir = StaticDiagnosisFixtureSources.compileAopWeavingSources(tempDir.resolve("weave"))
        // 塞一个损坏的 class 文件。索引侧对读不动的 class 没有容错，会整体抛错 —— 修复前这条路径
        // 只记 warn 后 return，于是**这次构建一个类都不会被织入**而构建照常成功。
        // 修复后它必须变成中止构建，和逐类织入失败同等对待。
        Files.write(
            classesDir.resolve("fixture/aopweave/Broken.class"),
            byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04),
        )

        val project = ProjectBuilder.builder()
            .withName("weave-failure")
            .withProjectDir(Files.createTempDirectory("weave-failure").toFile())
            .build()
        val task = project.tasks.create("weaveTaboolibIocAop", WeaveTaboolibIocAopTask::class.java)
        task.classDirectories.from(classesDir.toFile())

        val error = assertFailsWith<GradleException>("织入失败必须中止构建") { task.weave() }
        assertContains(error.message.orEmpty(), "[taboolibIocWeaveAop]")
    }

    @Test
    fun weavesEligibleClassesWithoutFailing() {
        val classesDir = StaticDiagnosisFixtureSources.compileAopWeavingSources(tempDir.resolve("weave-ok"))

        val project = ProjectBuilder.builder()
            .withName("weave-ok")
            .withProjectDir(Files.createTempDirectory("weave-ok").toFile())
            .build()
        val task = project.tasks.create("weaveTaboolibIocAop", WeaveTaboolibIocAopTask::class.java)
        task.classDirectories.from(classesDir.toFile())

        task.weave()

        // 对照：无损坏输入时不得因为新增的失败判据而误中止。
        val woven = Files.walk(classesDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                .anyMatch { Files.readAllBytes(it).decodeToString().contains("\$ioc\$original") }
        }
        assertTrue(woven, "夹具里被切点命中的类应已被织入")
    }
}
