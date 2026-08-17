package gg.grounds.resourcepacks.velocity

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class SettingsRepositoryPolicyTest {
    @Test
    fun `plugin resolution supports Gradle properties and CI environment credentials`() {
        val settings = Files.readString(projectRoot().resolve("settings.gradle.kts"))

        assertTrue(settings.contains("https://maven.pkg.github.com/groundsgg/*"))
        assertTrue(
            Regex(
                    """providers\.gradleProperty\("github\.user"\)\.orNull\s*\?:\s*System\.getenv\("GITHUB_ACTOR"\)\s*\?:\s*""""
                )
                .containsMatchIn(settings)
        )
        assertTrue(
            Regex(
                    """providers\.gradleProperty\("github\.token"\)\.orNull\s*\?:\s*System\.getenv\("GITHUB_TOKEN"\)\s*\?:\s*""""
                )
                .containsMatchIn(settings)
        )
    }

    private fun projectRoot(): Path =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }
            .toPath()
}
