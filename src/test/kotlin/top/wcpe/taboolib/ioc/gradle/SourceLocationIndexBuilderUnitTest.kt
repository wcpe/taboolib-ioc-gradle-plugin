package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import top.wcpe.taboolib.ioc.gradle.analysis.InjectionPointDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.InjectionPointKind
import top.wcpe.taboolib.ioc.gradle.analysis.SourceFieldAnalysis
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
                InjectionPointDefinition(
                    ownerClassName = ownerClassName,
                    declarationName = fieldName,
                    dependencyType = dependencyType,
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
                ),
            ),
        )
    }
}
