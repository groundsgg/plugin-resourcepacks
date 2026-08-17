import org.gradle.api.JavaVersion
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("gg.grounds.base-conventions") version "0.8.0" apply false
    kotlin("jvm") version "2.2.20" apply false
}

group = "gg.grounds"
version = "0.1.0"

subprojects {
    apply(plugin = "gg.grounds.base-conventions")
    apply(plugin = "org.jetbrains.kotlin.jvm")

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
