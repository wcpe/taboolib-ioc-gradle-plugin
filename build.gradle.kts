import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.publish.maven.MavenPublication
import java.math.BigDecimal
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
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

val projectWebsite = "https://github.com/TabooLib/taboolib-ioc-gradle-plugin"
val projectScmConnection = "scm:git:https://github.com/TabooLib/taboolib-ioc-gradle-plugin.git"
val projectScmDeveloperConnection = "scm:git:ssh://git@github.com/TabooLib/taboolib-ioc-gradle-plugin.git"

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
    maven("https://repo.tabooproject.org/repository/releases/")
}

dependencies {
    implementation(gradleApi())
    implementation(kotlin("stdlib"))
    implementation("org.ow2.asm:asm:9.7.1")

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

gradlePlugin {
    website.set(projectWebsite)
    vcsUrl.set(projectWebsite)

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

publishing {
    repositories {
        mavenLocal()

        val publishRepoUrl = providers.gradleProperty("publish.repo.url")
            .orElse(providers.environmentVariable("MAVEN_PUBLISH_URL"))
            .orNull
            ?.trim()
            .orEmpty()
        if (publishRepoUrl.isNotEmpty()) {
            maven {
                name = "target"
                url = uri(publishRepoUrl)
                isAllowInsecureProtocol = publishRepoUrl.startsWith("http://")
                credentials {
                    username = providers.gradleProperty("publish.repo.username")
                        .orElse(providers.environmentVariable("MAVEN_PUBLISH_USERNAME"))
                        .orNull
                    password = providers.gradleProperty("publish.repo.password")
                        .orElse(providers.environmentVariable("MAVEN_PUBLISH_PASSWORD"))
                        .orNull
                }
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Taboolib IoC Gradle Plugin")
            description.set("Automates TabooLib IoC embedding and relocation for TabooLib consumer builds.")
            url.set(projectWebsite)
            scm {
                url.set(projectWebsite)
                connection.set(projectScmConnection)
                developerConnection.set(projectScmDeveloperConnection)
            }
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

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = BigDecimal("0.75")
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = BigDecimal("0.55")
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

tasks.register("printPublishTargets") {
    group = "publishing"
    description = "Prints the currently configured Maven publish target and common publish commands."
    doLast {
        val publishRepoUrl = providers.gradleProperty("publish.repo.url")
            .orElse(providers.environmentVariable("MAVEN_PUBLISH_URL"))
            .orNull
            ?.trim()
            .orEmpty()
        logger.lifecycle("[publish] version = ${project.version}")
        logger.lifecycle("[publish] local = publishToMavenLocal")
        logger.lifecycle("[publish] remote = ${if (publishRepoUrl.isEmpty()) "<not configured>" else publishRepoUrl}")
        logger.lifecycle("[publish] portal = publishPlugins (requires gradle.publish.key / gradle.publish.secret)")
    }
}

tasks.validatePlugins {
    enableStricterValidation = true
}