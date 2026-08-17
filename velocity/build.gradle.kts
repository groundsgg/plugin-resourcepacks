import com.github.gmazzo.buildconfig.BuildConfigExtension

plugins { id("gg.grounds.velocity-conventions") }

configure<BuildConfigExtension> { packageName("gg.grounds.generated") }

dependencies {
    compileOnly("gg.grounds:plugin-config-common:1.0.0")
    compileOnly("gg.grounds:plugin-config-velocity:1.0.0") {
        attributes {
            attribute(
                org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE,
                objects.named(org.gradle.api.attributes.Bundling.SHADOWED),
            )
        }
    }
    implementation("gg.grounds:resourcepacks-client:0.3.0")

    testImplementation("gg.grounds:plugin-config-common:1.0.0")
    testImplementation("gg.grounds:plugin-config-velocity:1.0.0") {
        attributes {
            attribute(
                org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE,
                objects.named(org.gradle.api.attributes.Bundling.SHADOWED),
            )
        }
    }
    testImplementation("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    testImplementation(kotlin("test"))
}
