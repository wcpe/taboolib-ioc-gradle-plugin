package top.wcpe.taboolib.ioc.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeAnalysisIndex
import top.wcpe.taboolib.ioc.gradle.analysis.ClassIndexEntry
import top.wcpe.taboolib.ioc.gradle.analysis.WovenResidueDetector

/**
 * §2.3.7 残渣识别与告警文案（不依赖 Gradle 语境，纯函数）。
 */
class WovenResidueDetectorUnitTest {

    @Test
    fun detectsOnlyOutputClassesImplementingWovenTarget() {
        val index = BytecodeAnalysisIndex(
            classIndex = listOf(
                entry("fixture.Woven", listOf("top.wcpe.taboolib.ioc.aop.WovenTarget")),
                entry("fixture.Clean", listOf("java.io.Serializable")),
                entry("dep.WovenDependency", listOf("top.wcpe.taboolib.ioc.aop.WovenTarget")),
            ),
            beanIndex = emptyList(),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )

        val detected = WovenResidueDetector.detectResidueClassNames(
            index,
            setOf("fixture.Woven", "fixture.Clean"),
        )

        assertEquals(
            listOf("fixture.Woven"),
            detected,
            "只应统计编译输出目录内、且实现了标记接口的类",
        )
    }

    @Test
    fun warningMessageIsHighRiskWhenWeavingDisabled() {
        val highRisk = WovenResidueDetector.warningMessage(listOf("fixture.Woven"), weaving = false)
        assertTrue(highRisk.contains("⚠️"), "weaving=false 的残渣告警应为高危（含 ⚠️）")
        assertTrue(highRisk.contains("clean"), "应给出 ./gradlew clean 建议")

        val normal = WovenResidueDetector.warningMessage(listOf("fixture.Woven"), weaving = true)
        assertTrue(normal.contains("ALREADY_WOVEN"), "weaving=true 的告警应说明已按 ALREADY_WOVEN(→WOVEN) 处理")
    }

    private fun entry(className: String, interfaces: List<String>) = ClassIndexEntry(
        className = className,
        packageName = className.substringBeforeLast('.'),
        sourceFile = null,
        superClassName = null,
        interfaceNames = interfaces,
    )
}
