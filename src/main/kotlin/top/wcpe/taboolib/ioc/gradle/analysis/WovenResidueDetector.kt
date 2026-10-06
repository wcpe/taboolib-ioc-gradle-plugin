package top.wcpe.taboolib.ioc.gradle.analysis

import top.wcpe.taboolib.ioc.gradle.weaving.WEAVING_MARKER_INTERFACES

/**
 * 「残渣」识别与告警文案（§2.3.7）。
 *
 * 背景：`WeaveTaboolibIocAopTask` 是**就地**改写 `compileXxx` 的输出目录，全仓无 un-weave。
 * 当 `compileXxx` 因源码未变而 UP-TO-DATE 时，上一轮写入的已织入字节会**跨构建残留**：
 * - build1 `weaving=true` 就地写入；
 * - build2 `weaving=false`（编译 UP-TO-DATE）→ `classDirectories` 仍是已织入字节。
 *
 * 本检测器负责在诊断任务读取 `classDirectories` 时**显式告警**（绝不静默）；
 * 与之配套的「诊断对残渣免疫」由 `StaticDiagnosisEngine.collectExposedInterfaces` 剔除
 * 织入标记接口保证（见 [WEAVING_MARKER_INTERFACES]）。两者合起来使诊断结论与
 * 「字节码是否已织入」**无关**，从而可复现。
 */
internal object WovenResidueDetector {

    /**
     * 检出「位于 `classDirectories`（编译输出）内、且实现了织入标记接口」的类名（点号 FQCN，已排序）。
     *
     * 只统计编译输出目录内的类：外部依赖 jar 由织入器写入（`classDirectories` 之外），
     * 不应被误判为本次就地织入的残渣。
     *
     * @param index 已构建的字节码索引（`classIndex.interfaceNames` 为点号形式）
     * @param outputClassNames `classDirectories` 下所有 `.class` 文件的点号 FQCN 集合
     */
    fun detectResidueClassNames(
        index: BytecodeAnalysisIndex,
        outputClassNames: Set<String>,
    ): List<String> =
        index.classIndex
            .asSequence()
            .filter { it.className in outputClassNames }
            .filter { entry -> entry.interfaceNames.any { it in WEAVING_MARKER_INTERFACES } }
            .map { it.className }
            .sorted()
            .toList()

    /**
     * 生成残渣告警文案。
     *
     * `weaving=false` 时为**高危告警**（jar 可能已被织入却不符当前开关），含 `./gradlew clean` 建议。
     */
    fun warningMessage(residueClassNames: List<String>, weaving: Boolean): String {
        val count = residueClassNames.size
        return if (weaving) {
            "[analyzeTaboolibIocBeans] 检测到已织入字节残留（$count 个类 implements WovenTarget）；" +
                "若来源于上一次构建的就地织入，本次 plan 已按 ALREADY_WOVEN(→WOVEN) 处理。如需干净重织，请执行 ./gradlew clean。"
        } else {
            "[analyzeTaboolibIocBeans] ⚠️ classDirectories 含上次织入残留，而当前 weaving=false；" +
                "最终 jar 可能已被织入，与 weaving=false 不符！请执行 ./gradlew clean 后重建。"
        }
    }
}
