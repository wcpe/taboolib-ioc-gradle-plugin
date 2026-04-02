package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import top.wcpe.taboolib.ioc.gradle.analysis.SourceTypeAliasIndexBuilder

class SourceTypeAliasIndexBuilderUnitTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun indexesKotlinTypeAliasDeclarations() {
        val sourceDir = tempDir.resolve("src/main/kotlin/com/example/typealias")
        sourceDir.createDirectories()
        sourceDir.resolve("Aliases.kt").writeText(
            """
            package com.example.typealias

            typealias GreetingBox = com.example.demo.MessageBox<String>
            typealias AuditGateway = com.example.audit.ExternalAuditGateway
            """.trimIndent(),
        )

        val aliases = SourceTypeAliasIndexBuilder.build(listOf(tempDir.resolve("src/main/kotlin")))

        assertTrue(aliases.any { it.aliasName == "GreetingBox" && it.targetType.contains("MessageBox<String>") })
        assertTrue(aliases.any { it.aliasName == "AuditGateway" && it.packageName == "com.example.typealias" })
    }
}