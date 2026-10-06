package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import top.wcpe.taboolib.ioc.gradle.analysis.InjectionPointDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.InjectionPointKind
import top.wcpe.taboolib.ioc.gradle.analysis.SourceFieldAnalysis
import top.wcpe.taboolib.ioc.gradle.analysis.SourceLocation
import top.wcpe.taboolib.ioc.gradle.analysis.SourceLocationIndexBuilder

class SourceLocationIndexBuilderUnitTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun ignoresPseudoAssignmentsInCommentsAndStrings() {
        val sourceRoot = tempDir.resolve("src/main/java/com/example/source")
        sourceRoot.createDirectories()
        sourceRoot.resolve("CommentStringConsumer.java").writeText(
            """
            package com.example.source;

            class CommentStringConsumer {
                ComponentService componentService;

                void message() {
                    // componentService = new ComponentService();
                    /* this.componentService = new ComponentService(); */
                    String text = "componentService = fake";
                    String other = "this.componentService = fake";
                }
            }

            class ComponentService {}
            """.trimIndent(),
        )

        val analysis = analyzeField(
            sourceDirectories = listOf(tempDir.resolve("src/main/java")),
            ownerClassName = "com.example.source.CommentStringConsumer",
            ownerPackage = "com.example.source",
            sourceFile = "CommentStringConsumer.java",
            fieldName = "componentService",
            dependencyType = "com.example.source.ComponentService",
        )

        assertFalse(analysis.hasInitializer)
        assertFalse(analysis.hasManualAssignment)
    }

    @Test
    fun detectsKotlinMultilineInitializersAndDelegatedProperties() {
        val sourceRoot = tempDir.resolve("src/main/kotlin/com/example/source")
        sourceRoot.createDirectories()
        sourceRoot.resolve("ComponentService.kt").writeText(
            """
            package com.example.source

            class ComponentService
            """.trimIndent(),
        )
        sourceRoot.resolve("KotlinConsumers.kt").writeText(
            """
            package com.example.source

            import kotlin.properties.Delegates

            class KotlinMultilineInitializerConsumer {
                var componentService: ComponentService =
                    createService(
                        "componentService = inside-string"
                    )

                private fun createService(message: String): ComponentService {
                    return ComponentService()
                }
            }

            class KotlinDelegateConsumer {
                var componentService: ComponentService by Delegates.notNull()
            }
            """.trimIndent(),
        )

        val multilineAnalysis = analyzeField(
            sourceDirectories = listOf(tempDir.resolve("src/main/kotlin")),
            ownerClassName = "com.example.source.KotlinMultilineInitializerConsumer",
            ownerPackage = "com.example.source",
            sourceFile = "KotlinConsumers.kt",
            fieldName = "componentService",
            dependencyType = "com.example.source.ComponentService",
        )
        val delegateAnalysis = analyzeField(
            sourceDirectories = listOf(tempDir.resolve("src/main/kotlin")),
            ownerClassName = "com.example.source.KotlinDelegateConsumer",
            ownerPackage = "com.example.source",
            sourceFile = "KotlinConsumers.kt",
            fieldName = "componentService",
            dependencyType = "com.example.source.ComponentService",
        )

        assertTrue(multilineAnalysis.hasInitializer)
        assertFalse(multilineAnalysis.hasManualAssignment)
        assertTrue(delegateAnalysis.hasInitializer)
        assertFalse(delegateAnalysis.hasManualAssignment)
    }

    @Test
    fun detectsRealManualAssignments() {
        val sourceRoot = tempDir.resolve("src/main/kotlin/com/example/source")
        sourceRoot.createDirectories()
        sourceRoot.resolve("ManualAssignmentConsumer.kt").writeText(
            """
            package com.example.source

            class ManualAssignmentConsumer {
                lateinit var componentService: ComponentService

                fun wire() {
                    val note = "componentService = fake"
                    this.componentService = createService()
                }

                private fun createService(): ComponentService {
                    return ComponentService()
                }
            }

            class ComponentService
            """.trimIndent(),
        )

        val analysis = analyzeField(
            sourceDirectories = listOf(tempDir.resolve("src/main/kotlin")),
            ownerClassName = "com.example.source.ManualAssignmentConsumer",
            ownerPackage = "com.example.source",
            sourceFile = "ManualAssignmentConsumer.kt",
            fieldName = "componentService",
            dependencyType = "com.example.source.ComponentService",
        )

        assertFalse(analysis.hasInitializer)
        assertTrue(analysis.hasManualAssignment)
    }

    @Test
    @DisplayName("私有状态类按完整 JVM 名称识别空值初始化")
    fun indexesPrivateNestedStateWithJvmOwnerName() {
        val source = """
            object Holder {
                private class State {
                    @Volatile
                    var service: Service? = null
                }
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        val analysis = nestedField("Holder${'$'}State")
        assertTrue(analysis.hasInitializer, "嵌套状态的显式空值初始化必须被识别")
        assertFieldLine(analysis, "var service: Service? = null")
    }

    @Test
    @DisplayName("多层嵌套类按完整所有者识别手工赋值")
    fun indexesMultipleNestedLevelsAndManualAssignments() {
        nestedSource("""
            class Outer {
                class Middle {
                    class State {
                        lateinit var service: Service
                        fun register() { service = Service() }
                    }
                }
            }
            class Service
        """)
        val analysis = nestedField("Outer${'$'}Middle${'$'}State")
        assertFalse(analysis.hasInitializer, "未赋初值的字段不能被视为已初始化")
        assertTrue(analysis.hasManualAssignment, "多层嵌套类内的真实赋值必须被识别")
    }

    @Test
    @DisplayName("同文件的同名嵌套类和顶层类不串读初始化")
    fun isolatesNestedOwnersAndTopLevelTypeWithSameSimpleName() {
        nestedSource("""
            class First {
                class State { var service: Service? = null }
            }
            class Second {
                class State { lateinit var service: Service }
            }
            class State { lateinit var service: Service }
            class Service
        """)
        assertTrue(nestedField("First${'$'}State").hasInitializer, "第一个所有者有初值")
        assertFalse(nestedField("Second${'$'}State").hasInitializer, "第二个所有者不能借用第一个初值")
        assertFalse(nestedField("State").hasInitializer, "顶层同名类型不能借用嵌套类初值")
    }

    @Test
    @DisplayName("不同源文件的同名嵌套类型保持隔离")
    fun isolatesSameNamedNestedTypesAcrossSourceFiles() {
        nestedSource("class First { class State { var service: Service? = null } }", "First.kt")
        nestedSource("class Second { class State { lateinit var service: Service } }", "Second.kt")
        assertTrue(nestedField("First${'$'}State", sourceFile = "First.kt").hasInitializer, "第一个文件有初值")
        val second = nestedField("Second${'$'}State", sourceFile = "Second.kt")
        assertFalse(second.hasInitializer, "第二个文件不能借用同名类型初值")
        assertTrue(second.location.sourcePath.endsWith("Second.kt"), "位置必须来自实际所有者源文件")
    }

    @Test
    @DisplayName("注释字符串及类头闭包的花括号不改变类型作用域")
    fun ignoresPseudoTypesAndBracesInNonCodeSegments() {
        val quotes = "\"\"\""
        val source = """
            /* class Fake { } */
            class Outer(val callback: () -> Unit = { println("}") }) {
                val text = "} class Fake {"
                val character = '}'
                val multiline = ${quotes}class Fake { }${quotes}
                // class Fake { }
                class State { var service: Service? = null }
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        assertTrue(nestedField("Outer${'$'}State").hasInitializer, "伪类型及闭包不得破坏真实嵌套关系")
    }

    @Test
    @DisplayName("外层类在嵌套声明前后的字段均可定位")
    fun resolvesOuterFieldsBeforeAndAfterNestedDeclaration() {
        val source = """
            class Outer {
                lateinit var before: Service
                class State { var service: Service? = null }
                lateinit var after: Service
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        val before = nestedField("Outer", "before")
        val after = nestedField("Outer", "after")
        assertFieldLine(before, "lateinit var before: Service")
        assertFieldLine(after, "lateinit var after: Service")
        assertFalse(after.hasInitializer, "后续外层字段不得借用内层字段初值")
    }

    @Test
    @DisplayName("内层同名字段的手工赋值不能抑制外层遗漏注入")
    fun excludesNestedAssignmentsFromOuterFieldAnalysis() {
        nestedSource("""
            class Outer {
                lateinit var service: Service
                class State {
                    lateinit var service: Service
                    fun register() { service = Service() }
                }
            }
            class Service
        """)
        val outer = nestedField("Outer")
        assertFalse(outer.hasInitializer, "外层字段没有初值")
        assertFalse(outer.hasManualAssignment, "内层赋值不能被视为外层装配")
        assertTrue(nestedField("Outer${'$'}State").hasManualAssignment, "内层自己的赋值仍应生效")
    }

    @Test
    @DisplayName("兄弟嵌套类的后续赋值不能越界抑制真实遗漏")
    fun boundsNestedFieldAnalysisAtActualClosingBrace() {
        nestedSource("""
            class Outer {
                class Missing { lateinit var service: Service }
                fun unrelated() { val service = Service() }
                class Assigned {
                    lateinit var service: Service
                    fun register() { service = Service() }
                }
            }
            class Service
        """)
        val missing = nestedField("Outer${'$'}Missing")
        assertFalse(missing.hasInitializer, "第一个嵌套类型没有初值")
        assertFalse(missing.hasManualAssignment, "类型结束后的同名赋值不能越界进入")
        assertTrue(nestedField("Outer${'$'}Assigned").hasManualAssignment, "兄弟类自己的赋值应识别")
    }

    @Test
    @DisplayName("同一行的 Java 嵌套声明不遮蔽外层同名字段")
    fun isolatesInlineJavaNestedAndOuterFields() {
        nestedSource("""
            class Outer { static class State { Service service = null; } Service service; }
            class Service {}
        """, "Nested.java")
        assertTrue(nestedField("Outer${'$'}State", sourceFile = "Nested.java").hasInitializer, "内层字段有初值")
        val outer = nestedField("Outer", sourceFile = "Nested.java")
        assertFalse(outer.hasInitializer, "外层同名字段不能借用同一行内层初值")
        assertFalse(outer.hasManualAssignment, "外层同名字段没有后续手工赋值")
    }

    @Test
    @DisplayName("无类体的 Kotlin 类型不能拥有下一类型的类体")
    fun doesNotNestFollowingTypeInsideBodylessKotlinTypes() {
        nestedSource("""
            class Bodyless
            data class DataBodyless(val id: Int)
            class Outer { class State { var service: Service? = null } }
            class Service
        """)
        assertTrue(nestedField("Outer${'$'}State").hasInitializer, "后续类体必须属于真实类型")
        assertNull(nestedFieldOrNull("Bodyless${'$'}Outer${'$'}State"), "无类体类型不得误拥有后续类型")
        assertNull(nestedFieldOrNull("DataBodyless${'$'}Outer${'$'}State"), "无类体数据类不得误拥有后续类型")
    }

    @Test
    @DisplayName("方法内局部类型不能冒充外层成员或抑制外层字段")
    fun doesNotBindNamedLocalTypeAsMemberClass() {
        val source = """
            class Outer {
                fun create() {
                    class Local { var service: Service? = null }
                }
                lateinit var service: Service
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        assertNull(nestedFieldOrNull("Outer${'$'}Local"), "方法局部类不能绑定为成员嵌套类")
        val outer = nestedField("Outer")
        assertFieldLine(outer, "lateinit var service: Service")
        assertFalse(outer.hasInitializer, "局部类初值不能抑制外层字段")
        assertFalse(outer.hasManualAssignment, "局部类赋值不能充当外层装配")
    }

    @Test
    @DisplayName("匿名对象同名字段不能抑制外层真实遗漏")
    fun excludesAnonymousObjectFieldsFromOuterAnalysis() {
        val source = """
            class Outer {
                val callback = object : Runnable {
                    var service: Service? = null
                    override fun run() {}
                }
                lateinit var service: Service
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        val outer = nestedField("Outer")
        assertFieldLine(outer, "lateinit var service: Service")
        assertFalse(outer.hasInitializer, "匿名对象字段初值不得用于外层字段")
        assertFalse(outer.hasManualAssignment, "匿名对象赋值不得用于外层装配")
    }

    @Test
    @DisplayName("无名伴生对象同名字段不能抑制外层真实遗漏")
    fun excludesUnnamedCompanionFieldsFromOuterAnalysis() {
        val source = """
            class Outer {
                companion object {
                    var service: Service? = null
                }
                lateinit var service: Service
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        val outer = nestedField("Outer")
        assertFieldLine(outer, "lateinit var service: Service")
        assertFalse(outer.hasInitializer, "伴生对象字段初值不得用于外层实例字段")
    }

    @Test
    @DisplayName("多行泛型类头不能丢失真实类体与字段初值")
    fun retainsBodyForMultilineGenericClassHeader() {
        val source = """
            class GenericOwner<
                T,
                R
            > {
                var service: Service? = null
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        val owner = nestedField("GenericOwner")
        assertFieldLine(owner, "var service: Service? = null")
        assertTrue(owner.hasInitializer, "完整泛型类头后的初值必须保持可识别")
    }

    // ==================== R1：类头换行不得丢失类体 ====================
    //
    // 旧实现用「续行关键字白名单」判定类头是否续行，任何未列举的合法换行都会让类头被误判为
    // 结束：bodyStart 变 null，整个类体不在条目范围内，字段初值事实全部丢失，合法代码被
    // 误报 missing-inject-annotation 并中断构建。以下四条各钉住一种写法。

    @Test
    @DisplayName("R1：注解构造器另起一行时类体必须完整")
    fun keepsBodyWhenAnnotatedConstructorStartsOnNextLine() {
        val source = """
            class Foo
                @Inject constructor(val a: Int) {
                var service: Service? = null
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        val analysis = nestedField("Foo")
        assertFieldLine(analysis, "var service: Service? = null")
        assertTrue(analysis.hasInitializer, "注解构造器换行时类体丢失")
    }

    @Test
    @DisplayName("R1：where 子句另起一行时类体必须完整")
    fun keepsBodyWhenWhereClauseStartsOnNextLine() {
        val source = """
            class Foo<T>
                where T : Comparable<T> {
                var service: Service? = null
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        val analysis = nestedField("Foo")
        assertFieldLine(analysis, "var service: Service? = null")
        assertTrue(analysis.hasInitializer, "where 子句换行时类体丢失")
    }

    @Test
    @DisplayName("R1：Java extends 位于行尾时类体必须完整")
    fun keepsBodyWhenJavaExtendsEndsTheLine() {
        val source = """
            class Foo extends
                Bar {
                Service service = null;
            }
            class Bar {}
            class Service {}
        """.trimIndent()
        nestedSource(source, "Extends.java")
        val analysis = nestedField("Foo", sourceFile = "Extends.java")
        assertFieldLine(analysis, "Service service = null;")
        assertTrue(analysis.hasInitializer, "extends 行尾换行时类体丢失")
    }

    @Test
    @DisplayName("R1：Java implements 位于行尾时类体必须完整")
    fun keepsBodyWhenJavaImplementsEndsTheLine() {
        val source = """
            class Foo implements
                Runnable {
                Service service = null;
                public void run() {}
            }
            class Service {}
        """.trimIndent()
        nestedSource(source, "Implements.java")
        val analysis = nestedField("Foo", sourceFile = "Implements.java")
        assertFieldLine(analysis, "Service service = null;")
        assertTrue(analysis.hasInitializer, "implements 行尾换行时类体丢失")
    }

    @Test
    @DisplayName("R1：无类体类型不得吞掉紧随其后的声明")
    fun doesNotSwallowFollowingDeclarationAfterBodylessType() {
        val source = """
            class Bodyless
            fun helper() {
                val service = 1
            }
            class Outer { class State { var service: Service? = null } }
            class Service
        """.trimIndent()
        nestedSource(source)
        assertTrue(nestedField("Outer\$State").hasInitializer, "后续类型的类体必须完整")

        // 这里刻意不再断言 `nestedFieldOrNull("Bodyless\$Outer\$State")` / `"Bodyless\$helper"` 为 null：
        // 嵌套命名要求 `depth == parent.bodyDepth + 1`，外层类型在顶层（depth == 0）时 bodyDepth 不可能
        // 为 -1，故 `Bodyless$X` 结构上不可能存在；`helper` 又是 `fun`，类型正则也吃不下。两条断言恒真。
        // 改为同一夹具把外层换成**真实成员类**：它必须产生 `Bodyless$State`，证明该入口本身有效、
        // 上面那条断言不是因为入口永远解析不出东西才成立的。
        nestedSource(
            """
            class Bodyless { class State { var service: Service? = null } }
            class Service
            """.trimIndent(),
        )
        assertNotNull(nestedFieldOrNull("Bodyless\$State"), "真实成员类必须得到 Bodyless\$ 前缀，证明该入口有效")
    }

    @Test
    @DisplayName("R1：超过旧字符上限的长类头不得丢失真实类体")
    fun retainsBodyForLongMultilineClassHeader() {
        // 40 行、每行 60+ 字符的父类型列表让类头累计超过 2000 字符，但仍远在行数上限之内。
        // 按字符截断时 `headerContinuesAt` 会提前判定「不续行」：bodyStart 置空，整个类体不在条目
        // 范围内，字段初值事实全部丢失，合法字段被误报 missing-inject-annotation 并中断构建。
        val parents = (1..40).joinToString(",\n") { index ->
            "    com.example.source.verylongpackage.ParentTypeWithLongName$index"
        }
        val source = "class Huge :\n$parents {\n    var service: Service? = null\n}\nclass Service"
        nestedSource(source)
        val analysis = nestedField("Huge")
        assertFieldLine(analysis, "var service: Service? = null")
        assertTrue(analysis.hasInitializer, "长类头被按字符截断，类体丢失")
    }

    @Test
    @DisplayName("R1：注解站点目标必须触发新声明熔断")
    fun doesNotSwallowFollowingDeclarationAfterUseSiteTargetAnnotation() {
        // `@get:Inject val x` 缺少判据时熔断失灵：回溯会继续向下找到函数体的 `{` 并当成类体，
        // 函数内的局部类随之被挂成 `Bodyless$Local` 这样的假成员名（局部类不是成员，绝不该进索引）。
        nestedSource(
            """
            class Bodyless
            @get:Inject val service: Service = Service()
            fun helper() {
                class Local { var service: Service? = null }
            }
            class Service
            """.trimIndent(),
        )
        assertNotNull(resolveOwner("Bodyless"), "类型条目不得消失")
        assertNull(nestedFieldOrNull("Bodyless\$Local"), "站点目标注解行必须触发熔断")
    }

    @Test
    @DisplayName("R1：行首 context(...) 接收者必须触发新声明熔断")
    fun doesNotSwallowFollowingDeclarationAfterContextReceiverLine() {
        // Kotlin 上下文接收者写在声明最前面（`context(Logger) val x = ...`），与站点目标注解同理：
        // 认不出来时熔断失灵，函数体内的局部类被挂成无类体类型的假成员名。
        nestedSource(
            """
            class Bodyless
            context(Logger) val service: Service = Service()
            fun helper() {
                class Local { var service: Service? = null }
            }
            class Service
            """.trimIndent(),
        )
        assertNotNull(resolveOwner("Bodyless"), "类型条目不得消失")
        assertNull(nestedFieldOrNull("Bodyless\$Local"), "context(...) 声明行必须触发熔断")
    }

    @Test
    @DisplayName("R1：多余的 `;` 必须让类头立即收口")
    fun doesNotSwallowFollowingDeclarationAfterSemicolonTerminatedHeader() {
        // 拼接 / 截断类病态输入里类头以 `;` 收尾：同一行的 `;` 由 headerRange 早退，
        // 下一行开头的 `;` 由 headerContinuesAt 熔断。两处都必须认定为「类头到此为止」，
        // 否则回溯会继续向下找到函数体的 `{` 并据为己有，函数内的局部类被挂成假成员名。
        nestedSource(
            """
            class InlineTrailing : Marker;
            fun inlineHelper() {
                class InlineLocal { var service: Service? = null }
            }
            class NextLineTrailing
            ;
            fun nextLineHelper() {
                class NextLineLocal { var service: Service? = null }
            }
            class Service
            interface Marker
            """.trimIndent(),
        )
        assertNotNull(resolveOwner("InlineTrailing"), "同一行 `;` 收尾的类型条目不得消失")
        assertNotNull(resolveOwner("NextLineTrailing"), "下一行 `;` 收尾的类型条目不得消失")
        assertNull(nestedFieldOrNull("InlineTrailing\$InlineLocal"), "同一行 `;` 必须让类头立即收口")
        assertNull(nestedFieldOrNull("NextLineTrailing\$NextLineLocal"), "下一行 `;` 必须让类头立即收口")
    }

    @Test
    @DisplayName("R1：以 `}` 开头的一行必须触发新声明熔断")
    fun doesNotSwallowFollowingDeclarationAfterClosingBraceLine() {
        // 截断 / 拼接类病态输入里，无类体类型后面直接跟一行 `}`。不认作「新声明」时，
        // 回溯会继续向下找到函数体的 `{` 并据为己有，函数内的局部类被挂成 Bodyless$Local。
        nestedSource(
            """
            class Bodyless
            }
            fun helper() {
                class Local { var service: Service? = null }
            }
            class Service
            """.trimIndent(),
        )
        assertNotNull(resolveOwner("Bodyless"), "类型条目不得消失")
        assertNull(nestedFieldOrNull("Bodyless\$Local"), "以 `}` 开头的一行必须触发熔断")
    }

    @Test
    @DisplayName("R1：超过回溯行数上限的超长类头必须退化为安全形态")
    fun keepsEntryAndDoesNotSwallowTypeAfterOverlongHeader() {
        // 类头长度超过回溯行数上限（当前 80 行）时无法定界，只要求 fail-safe：类型条目不得消失，
        // 也不得凭空声称字段已初始化（那是抑制诊断的方向，比多报危险）。
        val filler = (1..90).joinToString("\n") { index -> "    // 填充第 $index 行注释" }
        val source = "class Huge :\n$filler\n    Marker {\n    var service: Service? = null\n}\n" +
            "class Outer { class State { var service: Service? = null } }\nclass Service\ninterface Marker"
        nestedSource(source)
        val huge = assertNotNull(nestedFieldOrNull("Huge"), "回溯超限时类型条目不得消失")
        assertFalse(huge.hasInitializer, "无法定界的超长类头不得凭空声称字段已初始化")
        assertTrue(nestedField("Outer\$State").hasInitializer, "后续类型的类体不得被超长类头吞掉")
    }

    @Test
    @DisplayName("R1：类体缺一个右花括号时类型条目必须保留且字段事实不被抑制")
    fun keepsEntryAndFieldFactsWhenClassBodyIsUnbalanced() {
        // 文件被截断（少一个 `}`）时 `matchingBrace` 返回 null，只能退化成「只保留类头范围」。
        // 整条丢弃会让 analyzeField 返回 null，分析端反而失去抑制依据，真实的缺失注入被放过。
        nestedSource(
            """
            class Outer {
                lateinit var service: Service
            """.trimIndent(),
        )
        val analysis = assertNotNull(nestedFieldOrNull("Outer"), "源码不配平时类型条目不得消失")
        assertFalse(analysis.hasInitializer, "截断范围内没有初值事实，不得声称已初始化")
        assertFalse(analysis.hasManualAssignment, "截断范围内没有装配事实，不得声称已装配")
    }

    // ==================== R2：非代码片段不得制造幻影花括号 ====================

    @Test
    @DisplayName("R2：字符串模板内嵌字符串的右花括号不得制造幻影作用域")
    fun ignoresClosingBraceInsideStringTemplateLiteral() {
        val source = """
            class Outer {
                val text = "${'$'}{listOf("}")}"
                class State { var service: Service? = null }
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        assertTrue(nestedField("Outer\$State").hasInitializer, "嵌套类必须保持 JVM 名 Outer\$State")
        assertNull(nestedFieldOrNull("State"), "不得出现名为 fixture.nested.State 的假顶层条目")
    }

    @Test
    @DisplayName("R2：字符串模板内嵌字符串的左花括号不得让整文件丢条目")
    fun ignoresOpeningBraceInsideStringTemplateLiteral() {
        val source = """
            class Outer {
                val text = "${'$'}{listOf("{")}"
                class State { var service: Service? = null }
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        assertTrue(nestedField("Outer\$State").hasInitializer, "整文件不得零条目")
    }

    @Test
    @DisplayName("R2：Kotlin 嵌套块注释里的花括号不得参与配对")
    fun ignoresBracesInsideNestedBlockComments() {
        // 注释里刻意放一个游离的 `{`：单层注释处理会在内层 `*/` 处提前退出，把 `{` 漏成代码，
        // 使后续所有花括号配对整体错位。游离的 `}` 不具备这个破坏力（无 open 时被静默忽略），
        // 所以这里必须用 `{` 才能真正钉住嵌套注释的处理。
        val source = """
            /* 外层注释 /* 内层注释 */ 外层仍在注释里 { */
            class Outer { class State { var service: Service? = null } }
            class Service
        """.trimIndent()
        nestedSource(source)
        assertTrue(nestedField("Outer\$State").hasInitializer, "嵌套块注释内的花括号参与了配对")
    }

    @Test
    @DisplayName("R2：多级嵌套字符串模板不得制造幻影花括号")
    fun ignoresNestedStringTemplatesInsideTemplateExpression() {
        val quotes = "\"\"\""
        // 三种写法各自钉住一层嵌套：模板里再包字符串再包模板、lambda 花括号里的模板、
        // 以及 `"""` 里带 `${}`。任何一层退出时回到错误的状态，都会把后续的引号或花括号
        // 漏成代码，制造幻影花括号。
        val source = """
            class Outer {
                val nested = "${'$'}{ f { "${'$'}{a}" } }"
                val lambda = "${'$'}{ listOf(1).map { "${'$'}{it}" } }"
                val raw = ${quotes}${'$'}{ listOf("${'$'}{a}") }${quotes}
                class State { var service: Service? = null }
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        assertTrue(nestedField("Outer\$State").hasInitializer, "嵌套模板必须保持 JVM 名 Outer\$State")
        assertNull(nestedFieldOrNull("State"), "嵌套模板不得制造顶层假条目")
    }

    @Test
    @DisplayName("R2：字符串模板表达式里的行注释不得泄漏花括号")
    fun ignoresLineCommentInsideTemplateExpression() {
        // 注释里的 `{` 若不按注释屏蔽，会让模板表达式的配对深度多算一层：模板结束后仍停在
        // 模板状态，后面所有代码被当成模板内容屏蔽，`class State` 直接消失。
        val source = """
            class Outer {
                val text = "${'$'}{ listOf(1) // 注释里刻意放一个 { 花括号
                }"
                class State { var service: Service? = null }
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        assertTrue(nestedField("Outer\$State").hasInitializer, "模板内行注释的花括号泄漏进了代码区")
        assertNull(nestedFieldOrNull("State"), "模板内行注释不得制造顶层假条目")
    }

    // ==================== R3：EOF 边界不得静默丢弃类型 ====================

    @Test
    @DisplayName("R3：文件末行的无类体类型不得被丢弃")
    fun keepsBodylessTypeOnLastLine() {
        nestedSource("class Service\n")
        assertNotNull(resolveOwner("Service"), "文件末行的无类体类型被静默丢弃")
    }

    @Test
    @DisplayName("R3：文件末行的无类体接口不得被丢弃")
    fun keepsBodylessInterfaceOnLastLine() {
        nestedSource("class Outer { }\ninterface Marker\n")
        assertNotNull(resolveOwner("Marker"), "文件末行的无类体接口被静默丢弃")
    }

    // ==================== R4：object 关键字作标识符不得冒充类型 ====================

    @Test
    @DisplayName("R4：Java 中名为 object 的方法不得吞掉方法体赋值")
    fun doesNotTreatObjectMethodNameAsType() {
        val source = """
            class Outer {
                Service service;
                void object() {
                    service = new Service();
                }
            }
            class Service {}
        """.trimIndent()
        nestedSource(source, "ObjectMethod.java")
        val analysis = nestedField("Outer", sourceFile = "ObjectMethod.java")
        assertTrue(analysis.hasManualAssignment, "名为 object 的方法体被误当成类型作用域，吞掉了真实赋值")
    }

    // ==================== R5：by 委托表达式不得被当成类体 ====================

    @Test
    @DisplayName("R5：by object 委托的花括号不得被当成类体")
    fun keepsBodyAfterObjectDelegate() {
        val source = """
            class Foo : Bar by object : Bar {
                override fun x() {}
            } {
                var service: Service? = null
            }
            open class Bar { open fun x() {} }
            class Service
        """.trimIndent()
        nestedSource(source)
        val analysis = nestedField("Foo")
        assertFieldLine(analysis, "var service: Service? = null")
        assertTrue(analysis.hasInitializer, "by object 委托时类体丢失")
    }

    @Test
    @DisplayName("R5：普通 by 委托（最常见写法）必须照常工作")
    fun keepsBodyAfterPlainDelegate() {
        val source = """
            class Foo : Bar by delegate {
                var service: Service? = null
            }
            open class Bar
            class Service
        """.trimIndent()
        nestedSource(source)
        val analysis = nestedField("Foo")
        assertFieldLine(analysis, "var service: Service? = null")
        assertTrue(analysis.hasInitializer, "普通 by 委托时类体丢失")
    }

    // ==================== 代码审查确认的 3 个 CRITICAL ====================

    @Test
    @DisplayName("CRITICAL-1：Java 字符串里的模板起始符不得让后续类型消失")
    fun keepsTypesWhenJavaStringContainsTemplateMarker() {
        // Java 没有字符串模板：`"${"` 是普通字面量（表示占位符前缀时最自然的写法）。
        // 若按模板处理，`${` 之后的引号会被当成模板内字符串的起点，整个文件剩余部分被屏蔽，
        // 花括号配对失败抬高 depths，后续所有顶层类型拿不到 className 而消失。
        val source = """
            class Outer {
                String open = "${'$'}{";
                Service service = null;
            }
            class Service {}
        """.trimIndent()
        nestedSource(source, "TemplateMarker.java")
        val outer = nestedField("Outer", sourceFile = "TemplateMarker.java")
        assertFieldLine(outer, "Service service = null;")
        assertTrue(outer.hasInitializer, "Java 字符串里的模板起始符让文件剩余部分被屏蔽")
        assertNotNull(resolveOwner("Service", "TemplateMarker.java"), "后续顶层类型不得从索引消失")
    }

    @Test
    @DisplayName("CRITICAL-2：同名局部变量不得被当成字段手工装配")
    fun ignoresLocalVariableWithSameName() {
        // 误判为「已手工装配」会让真实的 missing-inject-annotation 候选被静默丢弃（漏报）。
        val source = """
            class Foo {
                lateinit var service: Service
                fun wire() {
                    val service = make()
                }
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        assertFalse(nestedField("Foo").hasManualAssignment, "同名局部变量被当成手工装配")
    }

    @Test
    @DisplayName("CRITICAL-2：其它对象的同名属性赋值不得被当成字段手工装配")
    fun ignoresForeignObjectAssignment() {
        val source = """
            class Foo {
                lateinit var service: Service
                fun wire(other: Foo) {
                    other.service = make()
                }
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        assertFalse(nestedField("Foo").hasManualAssignment, "other.service 被当成手工装配")
    }

    @Test
    @DisplayName("CRITICAL-2：同名比较行不得顶替字段声明锚点")
    fun doesNotAnchorDeclarationOnComparison() {
        // `service == null` 曾被当成声明行（`(?=\s*[:;=])` 会匹配 `=`），
        // 之后所有初值/手工装配判定都建立在错误锚点上。
        val source = """
            class Foo {
                fun check(other: Foo) {
                    if (other.service == null) return
                }
                lateinit var service: Service
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        val analysis = nestedField("Foo")
        assertFieldLine(analysis, "lateinit var service: Service")
        assertFalse(analysis.hasManualAssignment, "比较行不得被当成声明锚点")
    }

    @Test
    @DisplayName("CRITICAL-3：尾随 lambda 委托不得吞掉类体")
    fun keepsBodyAfterTrailingLambdaDelegate() {
        // 白名单只认 `by object`，`by lazy { ... } { ... }` 这类会把委托体的花括号当类体，
        // 真实类体随之被 ownedLines 屏蔽，合法字段被误报成缺失注入。
        val source = """
            class Foo : Bar by lazy {
                makeBar()
            } {
                var service: Service? = null
            }
            open class Bar
            class Service
        """.trimIndent()
        nestedSource(source)
        val analysis = nestedField("Foo")
        assertFieldLine(analysis, "var service: Service? = null")
        assertTrue(analysis.hasInitializer, "尾随 lambda 委托时类体丢失")
    }

    @Test
    @DisplayName("CRITICAL-3：匿名对象委托仍须照常工作（回归守卫）")
    fun keepsBodyAfterObjectDelegateStill() {
        val source = """
            class Foo : Bar by object : Bar {
                override fun x() {}
            } {
                var service: Service? = null
            }
            open class Bar { open fun x() {} }
            class Service
        """.trimIndent()
        nestedSource(source)
        val analysis = nestedField("Foo")
        assertFieldLine(analysis, "var service: Service? = null")
        assertTrue(analysis.hasInitializer, "匿名对象委托时类体丢失")
    }

    // ==================== 代码审查确认的 HIGH-4 / MEDIUM-6 ====================

    @Test
    @DisplayName("HIGH-4：for-each 里名为 object 的变量不得制造幻影作用域")
    fun doesNotTreatObjectLoopVariableAsType() {
        // Java 的 `object` 不是关键字，`for (Service object : list)` 里它是普通变量名。
        // 若照 Kotlin 规则当成类型声明，会生成一个带类体的幻影作用域，把循环体从外层的
        // ownedLines 里屏蔽掉，循环体内的真实赋值随之消失，合法字段被误报缺失注入。
        val source = """
            class Foo {
                Service service;
                void wire(java.util.List<Service> list) {
                    for (Service object : list) {
                        service = object;
                    }
                }
            }
            class Service {}
        """.trimIndent()
        nestedSource(source, "ObjectLoop.java")
        val analysis = nestedField("Foo", sourceFile = "ObjectLoop.java")
        assertTrue(analysis.hasManualAssignment, "for-each 的 object 变量吞掉了循环体内的赋值")
    }

    @Test
    @DisplayName("MEDIUM-6：无类体的 object 仍须进索引")
    fun keepsBodylessObject() {
        nestedSource("class Outer { }\nobject Singleton\n")
        assertNotNull(resolveOwner("Singleton"), "无类体的 object 被静默丢弃")
    }

    @Test
    @DisplayName("MEDIUM-6：无类体 object 不得吞掉紧随其后的类型")
    fun bodylessObjectDoesNotSwallowFollowingType() {
        val source = """
            object Singleton
            class Outer { class State { var service: Service? = null } }
            class Service
        """.trimIndent()
        nestedSource(source)
        assertNotNull(resolveOwner("Singleton"), "无类体的 object 被静默丢弃")
        assertTrue(nestedField("Outer\$State").hasInitializer, "无类体 object 不得吞掉后续类型")
    }

    // ==================== 编码容忍与同名冲突裁决 ====================

    @Test
    @DisplayName("非 UTF-8 源文件不得中断整个索引构建")
    fun keepsIndexingWhenSourceFileIsNotUtf8() {
        // 仓库里常混入 GBK / ANSI 编码的历史源码。严格 UTF-8 解码会抛 MalformedInputException，
        // 而调用链上没有 catch：整个分析任务会以**不带文件名**的错误中断，定位不到是哪个文件。
        nestedSource("class Outer { class State { var service: Service? = null } }\nclass Service")
        val legacy = "package fixture.nested\n\n// 中文注释在 GBK 下不是合法 UTF-8 字节\nclass Legacy\n"
        Files.write(tempDir.resolve("nestedSources").resolve("Legacy.kt"), legacy.toByteArray(charset("GBK")))
        assertTrue(nestedField("Outer\$State").hasInitializer, "一个非 UTF-8 文件不得让整份索引失效")
        assertNotNull(resolveOwner("Legacy"), "非 UTF-8 文件里的类型也须照常进索引")
    }

    @Test
    @DisplayName("同名类型的优胜者只由文件路径决定")
    fun selectsDeterministicWinnerOnClassNameConflict() {
        // 同名 FQCN 的优选取舍不能依赖 Files.walk 的产出顺序（由底层目录实现决定），
        // 否则同一份源码会产出内容不同的索引，污染 build cache 的命中结果。
        val sourceRoot = tempDir.resolve("conflictSources")
        sourceRoot.resolve("aa").createDirectories()
        sourceRoot.resolve("zz").createDirectories()
        Files.writeString(
            sourceRoot.resolve("aa/Dupe.kt"),
            "package fixture.conflict\n\nclass Dupe { var service: Service? = null }\nclass Service\n",
        )
        Files.writeString(
            sourceRoot.resolve("zz/Dupe.kt"),
            "package fixture.conflict\n\nclass Dupe { lateinit var service: Service }\nclass Service\n",
        )

        val index = SourceLocationIndexBuilder.build(listOf(sourceRoot))
        val analysis = assertNotNull(
            index.analyzeField(fieldPoint("fixture.conflict.Dupe", "fixture.conflict", "Dupe.kt", "service", "fixture.conflict.Service")),
            "同名冲突后类型条目仍须存在",
        )
        assertTrue(analysis.hasInitializer, "必须按路径字典序稳定选中 aa/Dupe.kt")
        assertTrue(index.degradationNotes.any { it.contains("fixture.conflict.Dupe") }, "同名冲突必须留下降级说明")
    }

    // ==================== 第二轮审查确认的缺陷 ====================

    @Test
    @DisplayName("模板状态按层隔离：跨行模板 + 内层模板不得让后续字段失去初值")
    fun keepsFieldFactsWhenTemplateSpansLines() {
        // 单一计数器会被内层 `${` 覆盖，退出提前发生、状态机在字符串字面量内部回到 CODE，
        // 字面量里的花括号泄漏成幻影花括号 → 类体失联 → 合法字段被误报缺失注入（ERROR）。
        val d = "$"
        val source = """
            class Foo {
                lateinit var service: Service
                val t = "${d}{ listOf("${d}{service}").map { it.length }
                    }"
                var service2: Service? = null
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        val analysis = nestedField("Foo", "service2")
        assertFieldLine(analysis, "var service2: Service? = null")
        assertTrue(analysis.hasInitializer, "跨行嵌套模板泄漏，后续字段的初值丢失")
    }

    @Test
    @DisplayName("this@Foo.service 必须被识别为手工装配")
    fun recognisesQualifiedThisAssignment() {
        // 排除 `.` 是为了挡掉 `other.service = ...`，但不能把显式限定到外层实例的 `this@Foo.` 一起挡掉。
        val source = """
            class Foo {
                lateinit var service: Service
                init {
                    listOf(1).forEach { this@Foo.service = Service() }
                }
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        assertTrue(nestedField("Foo").hasManualAssignment, "this@Foo.service 未被识别为手工装配")
    }

    @Test
    @DisplayName("长构造器参数列表不得耗尽类头回溯预算")
    fun keepsBodyWhenConstructorParameterListIsLong() {
        // 行数预算若在括号内也计数，一个正常的长参数列表就会耗尽它 → 类体失联 → 合法字段被误报。
        val params = (1..79).joinToString(",\n") { "        val p$it: Int = $it" }
        val source = "class Foo(\n$params\n    ) {\n        var service: Service? = null\n    }\n    class Service"
        nestedSource(source)
        assertTrue(nestedField("Foo").hasInitializer, "长参数列表耗尽了类头预算，类体失联")
    }

    @Test
    @DisplayName("源文件带 UTF-8 BOM 时类型仍须拿到包名")
    fun keepsPackageNameWhenSourceHasBom() {
        // 行首 U+FEFF 既不匹配 `\s` 也不被解码器丢弃：不剥掉的话 `^\s*package` 失配，整份文件
        // 拿到空包名，所有类型的 FQCN 退化成简单名，诊断的抑制随之全面失效 —— 合法字段被报成
        // 缺失注入（ERROR，默认阻断构建）。带 BOM 的文件是合法输入。
        val sourceRoot = tempDir.resolve("nestedSources")
        sourceRoot.createDirectories()
        val content = String(charArrayOf(0xFEFF.toChar())) + """
            package fixture.nested

            class Foo { var service: Service? = null }
            class Service
        """.trimIndent()
        sourceRoot.resolve("Nested.kt").writeText(content)
        assertTrue(
            nestedFieldOrNull("Foo") != null,
            "带 BOM 时 Foo 仍须以 fixture.nested.Foo 建索引，否则抑制失效、合法字段被误报",
        )
    }

    @Test
    @DisplayName("by 委托类的成员类型不得因下一条同行声明而丢条目")
    fun keepsMembersOfDelegatedClassWhenFollowingDeclarationHasBody() {
        // `isDelegateBraceGroup` 原先在 `{` 处直接 return true，同行新声明的熔断只在 '\n' 处求值，
        // 永远等不到：`class Other {` 的花括号被当成 Holder 的委托体，Holder 的 bodyStart 落到
        // Other 的类体上，Inner 因 parent 判定失败而整条被 mapNotNull 掉 → 其中的合法字段被误报。
        val source = """
            class Holder : Base by impl {
                class Inner { lateinit var service: Service }
            }
            class Other {
                fun x() {}
            }
            class Base { open fun x() {} }
            class Service
        """.trimIndent()
        nestedSource(source)
        assertFalse(
            nestedField("Holder\$Inner").hasInitializer,
            "Inner.service 无初值，条目必须存在且被如实判定（存在即说明未丢条目）",
        )
    }

    @Test
    @DisplayName("无类体类型不得吞掉下一条同行带花括号的声明")
    fun doesNotSwallowFollowingDeclarationIntoBodylessType() {
        // `headerContinuesAt` 的同类站点：`class Bodyless` 后接 `fun helper() { ... }` 时，
        // helper 的花括号被当成 Bodyless 的类体，函数内的局部类被挂成 `Bodyless$Local` 幻影嵌套名。
        val source = """
            class Bodyless
            fun helper() {
                class Local { var service: Service? = null }
            }
            class Service
        """.trimIndent()
        nestedSource(source)
        assertNull(
            nestedFieldOrNull("Bodyless\$Local"),
            "函数内的局部类不得被挂成幻影嵌套名",
        )
    }

    private fun nestedSource(source: String, fileName: String = "Nested.kt") {
        val sourceRoot = tempDir.resolve("nestedSources")
        sourceRoot.createDirectories()
        sourceRoot.resolve(fileName).writeText("package fixture.nested\n\n" + source.trimIndent())
    }

    private fun nestedField(owner: String, field: String = "service", sourceFile: String = "Nested.kt") =
        analyzeField(listOf(tempDir.resolve("nestedSources")), "fixture.nested.$owner", "fixture.nested",
            sourceFile, field, "fixture.nested.Service")

    private fun nestedFieldOrNull(owner: String): SourceFieldAnalysis? =
        SourceLocationIndexBuilder.build(listOf(tempDir.resolve("nestedSources"))).analyzeField(
            fieldPoint("fixture.nested.$owner", "fixture.nested", "Nested.kt", "service", "fixture.nested.Service"),
        )

    /** 只验证类型条目是否存在（不要求该类型有字段），用于 EOF 边界等「条目不得消失」的场景。 */
    private fun resolveOwner(owner: String, sourceFile: String = "Nested.kt"): SourceLocation? =
        SourceLocationIndexBuilder.build(listOf(tempDir.resolve("nestedSources"))).resolve(
            fieldPoint("fixture.nested.$owner", "fixture.nested", sourceFile, "service", "fixture.nested.Service"),
        )

    /**
     * 直接断言「解析出的行号指向的文件行确实包含这条声明」。
     *
     * 旧实现用 `source.lines().indexOfFirst { ... } + 3`：`+3` 依赖「夹具写入时前缀恰好是
     * package 行 + 一个空行」这一隐式约定，夹具一改就整体错位，且失败信息里看不出真实行内容。
     */
    private fun assertFieldLine(analysis: SourceFieldAnalysis, declaration: String) {
        val sourcePath = analysis.location.sourcePath
        val file = tempDir.resolve("nestedSources").resolve(sourcePath)
        val line = Files.readAllLines(file).getOrNull(analysis.location.sourceLine - 1)
        assertTrue(
            line?.contains(declaration) == true,
            "第 ${analysis.location.sourceLine} 行（$sourcePath）应指向「$declaration」，实际为「$line」",
        )
    }

    private fun analyzeField(
        sourceDirectories: List<Path>,
        ownerClassName: String,
        ownerPackage: String,
        sourceFile: String,
        fieldName: String,
        dependencyType: String,
    ): SourceFieldAnalysis {
        val index = SourceLocationIndexBuilder.build(sourceDirectories)
        return requireNotNull(
            index.analyzeField(
                fieldPoint(ownerClassName, ownerPackage, sourceFile, fieldName, dependencyType),
            ),
        )
    }

    private fun fieldPoint(owner: String, ownerPackage: String, sourceFile: String, field: String, dependency: String) =
        InjectionPointDefinition(
            ownerClassName = owner,
            declarationName = field,
            dependencyType = dependency,
            dependencyGenericType = null,
            ownerPackage = ownerPackage,
            sourceFile = sourceFile,
            sourcePath = null,
            sourceLine = null,
            sourceColumn = null,
            kind = InjectionPointKind.FIELD,
            parameterIndex = null,
            qualifierName = null,
            required = true,
        )
}
