import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    kotlin("jvm") version "2.3.0"
    `java-gradle-plugin`
    jacoco
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.0.0"
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
    maven("https://repo.tabooproject.org/repository/releases/")
}

dependencies {
    implementation(gradleApi())
    implementation(kotlin("stdlib"))

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

gradlePlugin {
    website.set("https://github.com/TabooLib/taboolib-gradle-plugin")
    vcsUrl.set("https://github.com/TabooLib/taboolib-gradle-plugin")

    plugins {
        create("taboolibIoc") {
            id = "top.wcpe.taboolib.ioc"
            displayName = "Taboolib IoC Gradle Plugin"
            description = "Automates TabooLib IoC embedding and relocation for TabooLib consumer builds."
            implementationClass = "top.wcpe.taboolib.ioc.gradle.TaboolibIocPlugin"
            tags.set(listOf("taboolib", "gradle-plugin", "minecraft", "bukkit", "ioc"))
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.named("jacocoTestReport"))
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showCauses = true
        showExceptions = true
        showStackTraces = true
    }
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.validatePlugins {
    enableStricterValidation = true
}