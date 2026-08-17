package gg.grounds.resourcepacks.velocity

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.yaml.snakeyaml.Yaml

class AutomationContractTest {
    private val root =
        generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .first { it.resolve("settings.gradle.kts").exists() }

    @Test
    fun `repository automation is bound to the restricted release contract`() {
        assertCi(parseWorkflow("ci.yml"))
        assertRelease(parseWorkflow("release.yml"))
        assertReleasePlease(parseWorkflow("release-please.yml"))
        assertLabels(parseWorkflow("labels.yml"))
        assertReleasePleaseFiles()
        assertDockerfile(parseDockerfile())
        assertVersionFile()
        assertCanonicalPullRequestTemplate()
        assertNoResourcePackPublishingSecrets()
        assertExactSecretBindings()
    }

    @Test
    fun `controlled mutations reject broad permissions missing clean check mutable checkout and missing tag trigger`() {
        val ci = workflowSource("ci.yml")
        val release = workflowSource("release.yml")
        val dockerfile = dockerfileSource()

        assertFails { assertCi(parseText(replaceOnce(ci, "packages: read", "packages: write"))) }
        assertFails { assertCi(parseText(replaceOnce(ci, "clean check", "check"))) }
        assertFails { assertCi(parseText(replaceOnce(ci, githubSha, "main"))) }
        assertFails { assertCi(parseText(replaceOnce(ci, "contents: read", "contents: write"))) }
        assertFails {
            assertRelease(parseText(replaceOnce(release, "tags: [v*]", "branches: [main]")))
        }
        assertFails { assertRelease(parseText(replaceOnce(release, "docker:", "other:"))) }
        assertFails {
            assertRelease(parseText(replaceOnce(release, "contents: read", "contents: write")))
        }
        assertFails {
            assertRelease(
                parseText(
                    replaceOnce(
                        release,
                        "    uses: groundsgg/.github/.github/workflows/docker-gradle-build-push.yml@main",
                        "    secrets: inherit\n    uses: groundsgg/.github/.github/workflows/docker-gradle-build-push.yml@main",
                    )
                )
            )
        }
        assertFails {
            assertDockerfile(
                parseDockerfile(
                    replaceOnce(
                        dockerfile,
                        "COPY --from=build /out/plugin.jar /jar/plugin.jar",
                        "COPY --from=build /out/plugin.jar /jar/other.jar",
                    )
                )
            )
        }
        assertFails {
            assertDockerfile(parseDockerfile(replaceOnce(dockerfile, "type=secret", "type=bind")))
        }
    }

    private fun assertCi(workflow: Map<String, Any?>) {
        assertEquals("CI", scalar(workflow, "name"))
        assertEquals(mapOf("branches" to listOf("main")), mapping(mapping(workflow, "on"), "push"))
        assertEquals(
            mapOf("branches" to listOf("main")),
            mapping(mapping(workflow, "on"), "pull_request"),
        )
        val jobs = mapping(workflow, "jobs")
        assertEquals(setOf("verify", "reusable"), jobs.keys)
        val verify = mapping(jobs, "verify")
        assertEquals("ubuntu-24.04", scalar(verify, "runs-on"))
        assertEquals(
            mapOf("contents" to "read", "packages" to "read"),
            mapping(verify, "permissions"),
        )
        assertEquals(
            mapOf(
                "GITHUB_ACTOR" to "${'$'}{{ github.actor }}",
                "GITHUB_TOKEN" to "${'$'}{{ github.token }}",
            ),
            mapping(verify, "env"),
        )
        val steps = verify["steps"] as? List<*> ?: error("verify must contain steps")
        val checkout =
            steps
                .map { it as? Map<*, *> ?: error("step must be a mapping") }
                .single { it["uses"] == "actions/checkout@v7" }
        assertEquals(
            mapOf("ref" to githubSha, "fetch-depth" to 1, "persist-credentials" to false),
            normalize(checkout["with"]) as Map<*, *>,
        )
        val java =
            steps
                .map { it as? Map<*, *> ?: error("step must be a mapping") }
                .single { it["uses"] == "actions/setup-java@v5" }
        assertEquals(
            mapOf("distribution" to "temurin", "java-version" to "25"),
            normalize(java["with"]) as Map<*, *>,
        )
        assertTrue(
            steps
                .mapNotNull { (it as? Map<*, *>)?.get("run") as? String }
                .contains("./gradlew --no-build-cache clean check"),
            "CI verification must execute an uncached clean check",
        )

        val reusable = mapping(jobs, "reusable")
        assertEquals("verify", scalar(reusable, "needs"))
        assertEquals(
            "groundsgg/.github/.github/workflows/gradle-ci.yml@main",
            scalar(reusable, "uses"),
        )
        assertEquals(
            mapOf("contents" to "read", "packages" to "read"),
            mapping(reusable, "permissions"),
        )
    }

    private fun assertRelease(workflow: Map<String, Any?>) {
        assertEquals("Release", scalar(workflow, "name"))
        assertEquals(setOf("push"), mapping(workflow, "on").keys)
        assertEquals(mapOf("tags" to listOf("v*")), mapping(mapping(workflow, "on"), "push"))
        val jobs = mapping(workflow, "jobs")
        assertEquals(setOf("maven", "docker"), jobs.keys)
        val reusable = mapping(jobs, "maven")
        assertEquals(
            "groundsgg/.github/.github/workflows/gradle-publish.yml@main",
            scalar(reusable, "uses"),
        )
        assertEquals(
            mapOf("contents" to "read", "packages" to "write"),
            mapping(reusable, "permissions"),
        )
        val docker = mapping(jobs, "docker")
        assertEquals(
            "groundsgg/.github/.github/workflows/docker-gradle-build-push.yml@main",
            scalar(docker, "uses"),
        )
        assertEquals(
            mapOf("contents" to "read", "packages" to "write"),
            mapping(docker, "permissions"),
        )
        assertFalse(
            docker.containsKey("secrets"),
            "Docker release must use only built-in GitHub credentials",
        )
    }

    private fun assertReleasePlease(workflow: Map<String, Any?>) {
        assertEquals("Release Please", scalar(workflow, "name"))
        assertEquals(setOf("push"), mapping(workflow, "on").keys)
        assertEquals(mapOf("branches" to listOf("main")), mapping(mapping(workflow, "on"), "push"))
        val jobs = mapping(workflow, "jobs")
        assertEquals(setOf("reusable"), jobs.keys)
        val reusable = mapping(jobs, "reusable")
        assertEquals(
            "groundsgg/.github/.github/workflows/release-please.yml@main",
            scalar(reusable, "uses"),
        )
        assertEquals(
            mapOf("contents" to "write", "issues" to "write", "pull-requests" to "write"),
            mapping(reusable, "permissions"),
        )
        assertEquals(
            "${'$'}{{ secrets.RELEASE_PLEASE_TOKEN }}",
            scalar(mapping(reusable, "secrets"), "RELEASE_PLEASE_TOKEN"),
        )
    }

    private fun assertLabels(workflow: Map<String, Any?>) {
        assertEquals("Label Sync", scalar(workflow, "name"))
        assertEquals(setOf("push", "pull_request"), mapping(workflow, "on").keys)
        assertEquals(
            mapOf("branches" to listOf("main"), "paths" to listOf(".github/workflows/labels.yml")),
            mapping(mapping(workflow, "on"), "push"),
        )
        assertEquals(
            mapOf("paths" to listOf(".github/workflows/labels.yml")),
            mapping(mapping(workflow, "on"), "pull_request"),
        )
        val jobs = mapping(workflow, "jobs")
        assertEquals(setOf("reusable"), jobs.keys)
        val reusable = mapping(jobs, "reusable")
        assertEquals(
            "groundsgg/.github/.github/workflows/label-sync.yml@main",
            scalar(reusable, "uses"),
        )
        assertEquals(
            mapOf("contents" to "read", "issues" to "write"),
            mapping(reusable, "permissions"),
        )
    }

    private fun assertReleasePleaseFiles() {
        assertEquals(
            mapOf(
                "packages" to
                    mapOf(
                        "." to
                            mapOf(
                                "release-type" to "simple",
                                "package-name" to "plugin-resourcepacks",
                                "initial-version" to "0.1.0",
                                "include-v-in-tag" to true,
                                "include-component-in-tag" to false,
                                "extra-files" to listOf("version.txt"),
                            )
                    )
            ),
            parseDocument(root.resolve("release-please-config.json").readText()),
        )
        assertEquals(
            mapOf("." to "0.0.0"),
            parseDocument(root.resolve(".release-please-manifest.json").readText()),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertVersionFile() {
        val version = strictVersion(root.resolve("version.txt").readText())
        val manifest =
            scalar(
                parseDocument(root.resolve(".release-please-manifest.json").readText())
                    as Map<String, Any?>,
                ".",
            )
                as String

        val bootstrap = manifest == "0.0.0" && version == "0.0.0"
        val released = manifest == version && atLeastInitialRelease(version)
        assertTrue(
            bootstrap || released,
            "version.txt and Release Please manifest must represent bootstrap or one released version",
        )
    }

    private fun assertDockerfile(instructions: List<DockerInstruction>) {
        assertEquals(
            "eclipse-temurin:25-jdk",
            instructions.first { it.keyword == "FROM" }.argument.substringBefore(" AS"),
        )
        assertTrue(
            instructions.any {
                it.keyword == "RUN" &&
                    it.argument.contains("--mount=type=secret,id=github_token,required=true")
            },
            "Docker build must read package credentials from a required BuildKit secret",
        )
        assertTrue(
            instructions.any { it.keyword == "RUN" && it.argument.contains(":velocity:shadowJar") },
            "Docker build must produce the final shaded Velocity JAR",
        )
        assertTrue(
            instructions.any {
                it.keyword == "COPY" &&
                    it.argument == "--from=build /out/plugin.jar /jar/plugin.jar"
            },
            "runtime image must carry exactly /jar/plugin.jar",
        )
        assertFalse(
            instructions.any { it.keyword in setOf("ENTRYPOINT", "CMD") },
            "plugin JAR image must not define a runtime command",
        )
    }

    private fun assertCanonicalPullRequestTemplate() {
        val template = root.resolve(".github/pull_request_template.md").readText()
        listOf(
                "# Pull Request",
                "## Description",
                "## Type of Change",
                "## Related Issues",
                "## Testing",
                "## Checklist",
            )
            .forEach { assertTrue(template.contains(it), "missing canonical PR section: $it") }
    }

    private fun assertNoResourcePackPublishingSecrets() {
        root.resolve(".github/workflows").toFile().listFiles()!!.forEach { workflow ->
            assertFalse(
                Regex("secrets\\.[A-Za-z0-9_]*(R2|CDN|RESOURCEPACK)", RegexOption.IGNORE_CASE)
                    .containsMatchIn(
                        stringsDeep(parseText(workflow.readText())).joinToString("\n")
                    ),
                "${workflow.name} must not use resourcepack delivery credentials",
            )
        }
    }

    private fun assertExactSecretBindings() {
        val workflows =
            root.resolve(".github/workflows").toFile().listFiles()!!.associate { workflow ->
                workflow.name to parseText(workflow.readText())
            }
        assertEquals(emptySet(), secretReferences(workflows.getValue("ci.yml")))
        assertEquals(emptySet(), secretReferences(workflows.getValue("release.yml")))
        assertEquals(emptySet(), secretReferences(workflows.getValue("labels.yml")))
        assertEquals(
            setOf("RELEASE_PLEASE_TOKEN"),
            secretReferences(workflows.getValue("release-please.yml")),
        )
        workflows.forEach { (name, workflow) ->
            assertFalse(hasInheritedSecrets(workflow), "$name must not inherit repository secrets")
        }
    }

    private val githubSha = "${'$'}{{ github.sha }}"

    private fun parseWorkflow(name: String): Map<String, Any?> = parseText(workflowSource(name))

    private fun workflowSource(name: String): String =
        root.resolve(".github/workflows/$name").readText()

    private fun dockerfileSource(): String = root.resolve("Dockerfile").readText()

    private fun parseDockerfile(source: String = dockerfileSource()): List<DockerInstruction> =
        source
            .lineSequence()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .fold(mutableListOf<String>()) { lines, line ->
                if (line.startsWith(" ") || line.startsWith("\\t")) {
                    lines[lines.lastIndex] += "\n${line.trim()}"
                } else {
                    lines += line.trim()
                }
                lines
            }
            .map { line ->
                DockerInstruction(line.substringBefore(' '), line.substringAfter(' ', ""))
            }
            .toList()

    @Suppress("UNCHECKED_CAST")
    private fun parseText(source: String): Map<String, Any?> =
        (Yaml().load<Any?>(source) as Map<Any?, Any?>).entries.associate { (key, value) ->
            (if (key == true) "on" else key.toString()) to normalize(value)
        }

    @Suppress("UNCHECKED_CAST")
    private fun normalize(value: Any?): Any? =
        when (value) {
            is Map<*, *> ->
                value.entries.associate { (key, child) ->
                    (if (key == true) "on" else key.toString()) to normalize(child)
                }
            is List<*> -> value.map(::normalize)
            else -> value
        }

    private fun parseDocument(source: String): Any? = normalize(Yaml().load<Any?>(source))

    private fun strictVersion(contents: String): String {
        val version = contents.removeSuffix("\n")
        assertTrue(
            contents == version || contents == "$version\n",
            "version.txt may contain only one optional trailing newline",
        )
        assertTrue(
            Regex(
                    "(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                )
                .matches(version),
            "version must be strict SemVer",
        )
        return version
    }

    private fun atLeastInitialRelease(version: String): Boolean {
        val (major, minor, patch) =
            version.substringBefore('-').substringBefore('+').split('.').map(String::toInt)
        return major > 0 || minor > 1 || (minor == 1 && patch >= 0)
    }

    private fun stringsDeep(value: Any?): List<String> =
        when (value) {
            is String -> listOf(value)
            is Map<*, *> ->
                value.entries.flatMap { (key, child) -> stringsDeep(key) + stringsDeep(child) }
            is List<*> -> value.flatMap(::stringsDeep)
            else -> emptyList()
        }

    private fun secretReferences(value: Any?): Set<String> =
        Regex("secrets\\.([A-Za-z0-9_]+)")
            .findAll(stringsDeep(value).joinToString("\n"))
            .map { it.groupValues[1] }
            .toSet()

    private fun hasInheritedSecrets(value: Any?): Boolean =
        when (value) {
            is Map<*, *> ->
                value.entries.any { (key, child) ->
                    (key == "secrets" && child == "inherit") || hasInheritedSecrets(child)
                }
            is List<*> -> value.any(::hasInheritedSecrets)
            else -> false
        }

    @Suppress("UNCHECKED_CAST")
    private fun mapping(value: Map<String, Any?>, key: String): Map<String, Any?> =
        value[key] as? Map<String, Any?> ?: error("$key must be a mapping")

    private fun scalar(value: Map<String, Any?>, key: String): Any? = value[key]

    private fun replaceOnce(source: String, old: String, new: String): String {
        assertTrue(source.contains(old), "test mutation anchor missing: $old")
        return source.replaceFirst(old, new)
    }

    private data class DockerInstruction(val keyword: String, val argument: String)
}
