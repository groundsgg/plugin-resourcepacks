rootProject.name = "plugin-resourcepacks"

include("velocity")

pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/groundsgg/*")
            credentials {
                username =
                    providers.gradleProperty("github.user").orNull
                        ?: System.getenv("GITHUB_ACTOR")
                        ?: ""
                password =
                    providers.gradleProperty("github.token").orNull
                        ?: System.getenv("GITHUB_TOKEN")
                        ?: ""
            }
        }
        gradlePluginPortal()
    }
}
