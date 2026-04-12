package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.gradle.testfixtures.ProjectBuilder
import top.wcpe.taboolib.ioc.gradle.backend.PackagingBackendId
import top.wcpe.taboolib.ioc.gradle.backend.StandaloneBackend
import top.wcpe.taboolib.ioc.gradle.model.ModuleDependencySpec
import top.wcpe.taboolib.ioc.gradle.model.ResolvedIocConfiguration
import top.wcpe.taboolib.ioc.gradle.model.TargetPackageResolution

class TaboolibIocSupportUnitTest {

    @Test
    fun parsesValidModuleDependencyNotation() {
        val parsed = ModuleDependencySpec.parse("com.example:demo:1.0.0")

        assertEquals("com.example", parsed.group)
        assertEquals("demo", parsed.name)
        assertEquals("1.0.0", parsed.version)
        assertEquals("com.example:demo:1.0.0", parsed.displayName)
    }

    @Test
    fun rejectsInvalidModuleDependencyNotation() {
        val error = assertFailsWith<TaboolibIocConfigurationException> {
            ModuleDependencySpec.parse("com.example:demo")
        }

        assertContains(error.message ?: "", "不是合法的 group:name:version")
    }

    @Test
    fun appendsIocSuffixOnlyWhenNeeded() {
        assertEquals(
            "com.example.demo.ioc",
            TargetPackageResolution("com.example.demo", "project.group").relocationTarget,
        )
        assertEquals(
            "com.example.demo.ioc",
            TargetPackageResolution("com.example.demo.ioc", "project.group").relocationTarget,
        )
    }

    @Test
    fun parsesBackendIdCaseInsensitively() {
        assertEquals(PackagingBackendId.TABOOLIB, PackagingBackendId.parse("taboolib"))
        assertEquals(PackagingBackendId.STANDALONE, PackagingBackendId.parse("STANDALONE"))
        assertNull(PackagingBackendId.parse("unknown"))
        assertNull(PackagingBackendId.parse(null))
    }

    @Test
    fun extensionDslMethodsUpdateDependencyProperties() {
        val project = ProjectBuilder.builder()
            .withName("dsl-methods")
            .withProjectDir(Files.createTempDirectory("dsl-methods").toFile())
            .build()
        val extension = project.extensions.create("taboolibIoc", TaboolibIocExtension::class.java)

        extension.dependency("com.example:demo:1.0.0")
        extension.useModule(":ioc-lib")

        assertEquals("com.example:demo:1.0.0", extension.dependencyNotation.get())
        assertEquals(":ioc-lib", extension.localProjectPath.get())
    }

    @Test
    fun reflectionSupportReadsGetterBooleanAndFieldValues() {
        val sample = ReflectionSample()

        assertEquals("demo", ReflectionSupport.readProperty(sample, "name"))
        assertEquals(true, ReflectionSupport.readProperty(sample, "enabled"))
        assertEquals("fallback", ReflectionSupport.readProperty(FieldOnlySample(), "secret"))
        assertNull(ReflectionSupport.readProperty(sample, "missing"))
    }

    @Test
    fun reflectionSupportInvokesMethodAndRejectsMissingSignature() {
        val receiver = MethodReceiver()

        ReflectionSupport.invokeMethod(receiver, "relocate", "from.pkg", "to.pkg")
        assertEquals("from.pkg" to "to.pkg", receiver.lastInvocation)

        val error = assertFailsWith<TaboolibIocConfigurationException> {
            ReflectionSupport.invokeMethod(receiver, "missing", "arg")
        }
        assertContains(error.message ?: "", "missing")
    }

    @Test
    fun standaloneBackendReturnsHelpfulMessages() {
        val project = ProjectBuilder.builder()
            .withName("standalone")
            .withProjectDir(Files.createTempDirectory("standalone").toFile())
            .build()
        val extension = project.extensions.create("taboolibIoc", TaboolibIocExtension::class.java).apply {
            backend.set(PackagingBackendId.STANDALONE)
            iocVersion.set(TaboolibIocResolver.DEFAULT_IOC_VERSION)
            targetPackage.set("com.example.demo")
        }
        val resolver = TaboolibIocResolver(project, extension)
        val configuration = ResolvedIocConfiguration(
            backendId = PackagingBackendId.STANDALONE,
            sourcePackage = TaboolibIocResolver.SOURCE_PACKAGE,
            targetPackage = TargetPackageResolution("com.example.demo", "taboolibIoc.targetPackage"),
            dependencySpec = ModuleDependencySpec(
                TaboolibIocResolver.DEFAULT_IOC_GROUP,
                TaboolibIocResolver.DEFAULT_IOC_ARTIFACT,
                TaboolibIocResolver.DEFAULT_IOC_VERSION,
            ),
            skipBecauseSubproject = false,
        )

        val result = StandaloneBackend.configure(project, resolver, configuration)

        assertFalse(result.configured)
        assertContains(result.message, "尚未实现独立打包引擎")
        val error = assertFailsWith<TaboolibIocConfigurationException> {
            StandaloneBackend.verify(project, resolver, configuration)
        }
        assertContains(error.message ?: "", "改用 backend = TABOOLIB")
    }

    private class ReflectionSample {
        fun getName(): String = "demo"

        fun isEnabled(): Boolean = true
    }

    private class FieldOnlySample {
        @Suppress("unused")
        private val secret: String = "fallback"
    }

    private class MethodReceiver {
        var lastInvocation: Pair<String, String>? = null

        fun relocate(source: String, target: String) {
            lastInvocation = source to target
        }
    }
}