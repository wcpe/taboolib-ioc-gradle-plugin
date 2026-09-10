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
    // 顺序敏感（本机代理环境实测结论，勿随意调整）：
    // - mavenLocal() 里的 ~/.m2 存在「只有 POM、没有主 jar」的半落盘模块（如 org.jacoco.agent:0.8.12）。
    //   Maven Resolver 的 _remote.repositories 会把该模块「钉」到来源仓库（central），
    //   于是 Gradle 认定该模块应由 central 提供、直接放弃 Gradle 本地缓存，
    //   而本机代理对 repo.maven.apache.org 的 TLS 握手会被远端重置 → 解析失败。
    // - 因此把「可达的全量镜像」放在 mavenLocal() 之前，让网络仓库先行兜底。
    maven("https://maven.aliyun.com/repository/public")
    maven("https://maven.wcpe.top/repository/maven-public/")
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
        maven {
            credentials {
                username = findProperty("username").toString()
                password = findProperty("password").toString()
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

tasks.register("ciTest") {
    group = "verification"
    description = "Runs unit tests, coverage verification, and plugin integration/server tests."
    dependsOn(tasks.named("test"))
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
    dependsOn(":plugin-integration-tests:test")
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