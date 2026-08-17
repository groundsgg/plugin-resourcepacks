import org.gradle.api.JavaVersion
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.zip.ZipFile

plugins {
    id("gg.grounds.base-conventions") version "0.8.0" apply false
    kotlin("jvm") version "2.2.20" apply false
}

group = "gg.grounds"

val semanticVersion =
    Regex(
        "(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
    )
val versionFileContents = file("version.txt").readText()
val versionFromFile = versionFileContents.removeSuffix("\n")
check(versionFileContents == versionFromFile || versionFileContents == "$versionFromFile\n") {
    "version.txt may contain only one optional trailing newline"
}
check(semanticVersion.matches(versionFromFile)) { "version.txt must contain a strict SemVer version" }
val resolvedVersion = providers.gradleProperty("versionOverride").orNull ?: versionFromFile
check(semanticVersion.matches(resolvedVersion)) { "versionOverride must contain a strict SemVer version" }
version = resolvedVersion

subprojects {
    apply(plugin = "gg.grounds.base-conventions")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    version = rootProject.version

    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/groundsgg/*")
            credentials {
                username = providers.gradleProperty("github.user").orNull ?: System.getenv("GITHUB_ACTOR") ?: ""
                password = providers.gradleProperty("github.token").orNull ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }

    extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(25) }
    extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_24
        targetCompatibility = JavaVersion.VERSION_24
    }
    tasks.withType<KotlinCompile>().configureEach { compilerOptions.jvmTarget.set(JvmTarget.JVM_24) }
    configurations.configureEach {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    dependencyLocking { lockAllConfigurations() }
}

project(":velocity") {
    val verifyNoOverridePluginMetadata =
        tasks.register("verifyNoOverridePluginMetadata") {
            dependsOn(tasks.named("shadowJar"))
            doLast {
                check(version.toString() == versionFromFile) {
                    "verifyNoOverridePluginMetadata must run without -PversionOverride"
                }
                val shadedJars =
                    layout.buildDirectory.dir("libs").get().asFile.listFiles()
                        ?.filter { it.extension == "jar" }
                        ?: emptyList()
                check(shadedJars.size == 1) { "expected exactly one shaded Velocity JAR" }
                ZipFile(shadedJars.single()).use { jar ->
                    val descriptor = jar.getEntry("velocity-plugin.json") ?: error("missing plugin metadata")
                    val metadata = jar.getInputStream(descriptor).bufferedReader().readText()
                    check("\"version\":\"$versionFromFile\"" in metadata) {
                        "plugin metadata version must match version.txt"
                    }
                    check("\"id\":\"plugin-config\",\"optional\":false" in metadata) {
                        "plugin metadata must require plugin-config"
                    }
                }
            }
        }
    tasks.named("check") { dependsOn(verifyNoOverridePluginMetadata) }
}
