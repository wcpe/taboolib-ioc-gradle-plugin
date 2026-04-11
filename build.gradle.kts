import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.25"
    `java-gradle-plugin`
    jacoco
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.1.1"
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

val projectWebsite = "https://github.com/wcpe/taboolib-ioc-gradle-plugin"
val projectScmConnection = "scm:git:https://github.com/wcpe/taboolib-ioc-gradle-plugin.git"
val projectScmDeveloperConnection = "scm:git:ssh://git@github.com/wcpe/taboolib-ioc-gradle-plugin.git"

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
    maven("https://repo.tabooproject.org/repository/releases/")
    maven("https://maven.wcpe.top/repository/maven-public/")
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
        maven {
            credentials {
                username = project.findProperty("username").toString()
                password = project.findProperty("password").toString()
            }
            authentication {
                create<BasicAuthentication>("basic")
            }
            val releasesRepoUrl = uri("https://maven.wcpe.top/repository/maven-releases/")
            val snapshotsRepoUrl = uri("https://maven.wcpe.top/repository/maven-snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
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
        logger.lifecycle("[publish] remote = ${publishRepoUrl.ifEmpty { "<not configured>" }}")
        logger.lifecycle("[publish] portal = publishPlugins (requires gradle.publish.key / gradle.publish.secret)")
    }
}

tasks.validatePlugins {
    enableStricterValidation = true
}