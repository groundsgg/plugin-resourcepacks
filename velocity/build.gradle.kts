import com.github.gmazzo.buildconfig.BuildConfigExtension

plugins { id("gg.grounds.velocity-conventions") }

configure<BuildConfigExtension> { packageName("gg.grounds.generated") }

dependencies {
    compileOnly("gg.grounds:plugin-config-common:1.0.0")
    compileOnly("gg.grounds:plugin-proxy-api:2.6.0")
    implementation("gg.grounds:library-i18n:0.4.4")
    compileOnly("gg.grounds:plugin-config-velocity:1.0.0") {
        attributes {
            attribute(
                org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE,
                objects.named(org.gradle.api.attributes.Bundling.SHADOWED),
            )
        }
    }
    implementation("gg.grounds:resourcepacks-client:1.0.0")

    testImplementation("gg.grounds:plugin-config-common:1.0.0")
    testImplementation("gg.grounds:plugin-proxy-api:2.6.0")
    testImplementation("gg.grounds:plugin-config-velocity:1.0.0") {
        attributes {
            attribute(
                org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE,
                objects.named(org.gradle.api.attributes.Bundling.SHADOWED),
            )
        }
    }
    testImplementation("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    testImplementation("org.yaml:snakeyaml:2.6")
    testImplementation(kotlin("test"))
}

val groundsGradleInitScript = providers.environmentVariable("GROUNDS_GRADLE_INIT_SCRIPT")

if (groundsGradleInitScript.isPresent) {
    tasks.named<org.gradle.api.tasks.testing.Test>("test") {
        dependsOn(tasks.named("shadowJar"))
        environment("GROUNDS_GRADLE_INIT_SCRIPT", groundsGradleInitScript.get())
    }
}
