plugins {
    kotlin("jvm") version "1.9.25"
}

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
    maven("https://repo.tabooproject.org/repository/releases/")
    maven("https://maven.wcpe.top/repository/maven-public/")
}

dependencies {
    testImplementation(project(":"))
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

val pluginUnderTestMetadata by configurations.creating

dependencies {
    pluginUnderTestMetadata(files(rootProject.layout.buildDirectory.dir("pluginUnderTestMetadata")))
}

tasks.test {
    useJUnitPlatform()
    dependsOn(rootProject.tasks.named("pluginUnderTestMetadata"))
    classpath += pluginUnderTestMetadata
    systemProperty("fixtureDir", layout.projectDirectory.dir("fixtures").asFile.absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
