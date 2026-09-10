package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeBeanIndexBuilder
import top.wcpe.taboolib.ioc.gradle.analysis.InjectionPointKind
import top.wcpe.taboolib.ioc.gradle.companionfixture.CompanionInjectionHolder
import top.wcpe.taboolib.ioc.gradle.companionfixture.CompanionNamedHolder

/**
 * B-P1-13 / C-P2-03：采集层 companion 跨类归并 + 字段静态性判定的**直接**单测。
 *
 * ## 背景
 *
 * `BytecodeBeanIndexBuilder` 的「`$annotations` 载体放行 + companion 跨类归并」分支
 * （`carrierAnnotations()` / `mergeCarrierAnnotations()` / `fromCompanion`）在既有
 * Java 夹具上**完全不触发** —— Java 不生成 `xxx$annotations` 载体；且即便 Kotlin 编译，
 * 运行时 `@Inject` 是 `@Target(FIELD)`，注解会直接落在外部类静态 backing field 上，
 * 同样不会生成 Companion 侧载体。
 *
 * 本测试使用 [top.wcpe.taboolib.ioc.gradle.companionfixture] 下 **PROPERTY 目标**注解编译出的
 * 真实 Kotlin 字节码，触发该分支：外部类静态字段本身**无**注解，注解只在 Companion 的
 * `getDep$annotations()` 载体上 —— 必须经跨类归并才能把注解合并回字段，并标记 `fromCompanion`。
 *
 * C-P2-03：其中一并直接断言静态字段能通过 `isStatic || fromCompanion` 门放行，
 * 而非仅依赖其它用例的间接覆盖。
 */
class CompanionCarrierMergeUnitTest {

    private val fixtureClassesDir: Path = classesDirOf(CompanionInjectionHolder::class.java)

    @Test
    fun mergesCompanionCarrierAnnotationsBackToOuterStaticField() {
        val index = BytecodeBeanIndexBuilder.build(listOf(fixtureClassesDir))

        // 关键断言：外部类静态字段无直接注解，只有 Companion 的 `getDep$annotations` 载体携带 @Inject。
        // 若跨类归并不生效，该字段不会出现在注入点索引中（既有 Java 夹具即如此）。
        val injection = index.injectionPointIndex.firstOrNull {
            it.ownerClassName == CompanionInjectionHolder::class.java.name &&
                it.kind == InjectionPointKind.FIELD &&
                it.declarationName == "dep"
        }
        assertNotNull(
            injection,
            "companion 属性的 @Inject 只在 Companion 的 \$annotations 载体上，必须经跨类归并才能采集为注入点",
        )
        assertEquals("java.lang.String", injection.dependencyType)
    }

    @Test
    fun carriesNamedQualifierFromCompanionCarrier() {
        val index = BytecodeBeanIndexBuilder.build(listOf(fixtureClassesDir))

        val injection = index.injectionPointIndex.firstOrNull {
            it.ownerClassName == CompanionNamedHolder::class.java.name &&
                it.kind == InjectionPointKind.FIELD &&
                it.declarationName == "dep"
        }
        assertNotNull(injection, "带 @Named 的 companion 字段同样应经载体归并后采集")
        assertEquals(
            "namedDep",
            injection.qualifierName,
            "@Named 的 value 必须从 Companion 载体一并归并回字段（否则运行期限定符丢失）",
        )
    }

    @Test
    fun companionMergedFieldPassesStaticFilter() {
        // C-P2-03：直接锁定字段静态性判定 —— companion backing field 是外部类静态字段，
        // 若无 `fromCompanion` 放行，会被 `!isStatic || isKotlinObjectSingleton || fromCompanion`
        // 门过滤掉（外部类并非 Kotlin object 单例，无 INSTANCE 字段）。
        val index = BytecodeBeanIndexBuilder.build(listOf(fixtureClassesDir))

        val fieldOwners = index.injectionPointIndex
            .filter { it.kind == InjectionPointKind.FIELD }
            .map { it.ownerClassName }
            .toSet()

        assertTrue(
            CompanionInjectionHolder::class.java.name in fieldOwners,
            "companion 静态字段必须通过 fromCompanion 放行成为注入点（isStatic 正确性直接覆盖，K25 依赖）",
        )
        assertTrue(
            CompanionNamedHolder::class.java.name in fieldOwners,
            "带 @Named 的 companion 静态字段必须通过 fromCompanion 放行成为注入点",
        )
    }

    /** 由已编译的类文件反推其所在的 classes 根目录，供 `BytecodeBeanIndexBuilder.build` 扫描。 */
    private fun classesDirOf(clazz: Class<*>): Path {
        val resource = clazz.name.replace('.', '/') + ".class"
        val url = clazz.classLoader.getResource(resource)
            ?: error("无法定位夹具类文件: $resource")
        val classFilePath = Path.of(url.toURI())
        // .../top/wcpe/taboolib/ioc/gradle/companionfixture/X.class → 上溯到 classes 根
        val packageDepth = clazz.name.count { it == '.' }
        var root = classFilePath.parent
        repeat(packageDepth) { root = root.parent }
        return root
    }
}
