import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.util.zip.ZipFile
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test

plugins {
    `java-library`
    `maven-publish`
    signing
    id("org.jreleaser") version "1.24.0"
}

group = "blue.bex"
version = determineProjectVersion()

val blueLanguagePublishedVersion = "3.1.0-rc.19"
val blueLanguageModelDeclaredCoordinate =
    "blue.language:blue-language-model:$blueLanguagePublishedVersion"
val blueLanguageCoreDeclaredCoordinate =
    "blue.language:blue-language-core:$blueLanguagePublishedVersion"
val blueLanguageMappingDeclaredCoordinate =
    "blue.language:blue-language-mapping:$blueLanguagePublishedVersion"
val blueContractsCoreDeclaredCoordinate =
    "blue.language:blue-contracts-core:$blueLanguagePublishedVersion"
val blueLanguageDeclaredCoordinate =
    "blue.language:blue-language-java:$blueLanguagePublishedVersion"
val blueLanguageFocusedCoordinates =
    listOf(
        blueLanguageModelDeclaredCoordinate,
        blueLanguageCoreDeclaredCoordinate,
        blueLanguageMappingDeclaredCoordinate,
        blueContractsCoreDeclaredCoordinate
    )
val blueLanguageFocusedModuleNames =
    linkedSetOf(
        "blue-language-model",
        "blue-language-core",
        "blue-language-mapping",
        "blue-contracts-core"
    )
val blueLanguageFocusedProjectPaths =
    blueLanguageFocusedModuleNames.associateWith { ":$it" }
val requiredBexRegistryIdentity =
    "sha256:23d282ec1c0bb016263922b1b49c369fdd537efdcf23e005eceeb888d7763fe1"
val requiredBexGasManifestIdentity =
    "sha256:41247c820d91a12fdfc17fd9e787a5d8d668d8acc5954fdcb131715bf9e6147d"
val requiredBexFixturePackageIdentity =
    "sha256:a1b7bb2b3687389409bc9d0aa450c734f7856d2bcb818c95f4d7ecb19095d20e"
val latestLanguageMigrationLock =
    layout.projectDirectory.file(
        "gradle/verification/latest-language-baseline.json"
    )
val blueLanguageCompositePath =
    providers.gradleProperty("blueLanguageCompositePath")
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
val blueLanguageDependencyMode =
    if (blueLanguageCompositePath == null) {
        "standalone-published"
    } else {
        "local-composite"
    }
val publishedBlueLanguageInspection =
    Properties().apply {
        file(
            "src/test/resources/hosted-release/" +
                "published-api-inspection.properties"
        ).inputStream().use(::load)
    }
val publishedBlueLanguageCoordinate =
    publishedBlueLanguageInspection.getProperty("coordinate")
val publishedBlueLanguageSha256 =
    publishedBlueLanguageInspection.getProperty("artifact.sha256")
val publishedBlueLanguageRepository =
    publishedBlueLanguageInspection.getProperty("repository")
val blueLanguageModuleVersionCache =
    File(
        gradle.gradleUserHomeDir,
        "caches/modules-2/files-2.1/blue.language/" +
            "blue-language-java/$blueLanguagePublishedVersion"
    )
val blueLanguageModuleVersionCacheInitiallyAbsent =
    !blueLanguageModuleVersionCache.exists()
val blueLanguageFocusedModuleVersionCaches =
    blueLanguageFocusedModuleNames.associateWith { moduleName ->
        File(
            gradle.gradleUserHomeDir,
            "caches/modules-2/files-2.1/blue.language/" +
                "$moduleName/$blueLanguagePublishedVersion"
        )
    }
val blueLanguageFocusedModuleVersionCachesInitiallyAbsent =
    blueLanguageFocusedModuleVersionCaches.mapValues { (_, cache) ->
        !cache.exists()
    }
val blueLanguageRequireFreshModuleCache =
    providers.gradleProperty("blueLanguageRequireFreshModuleCache")
        .map(String::toBoolean)
        .orElse(false)

base {
    archivesName.set("blue-bex-java")
}

repositories {
    // Release and developer resolution intentionally share one policy.
    // A same-GAV artifact from ~/.m2 must never masquerade as the published
    // Blue Language artifact in standalone-published mode.
    mavenCentral()
}

java {
    withJavadocJar()
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(8)
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Javadoc>().configureEach {
    javadocTool.set(
        javaToolchains.javadocToolFor {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    )
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        charSet = "UTF-8"
        addBooleanOption("notimestamp", true)
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

fun byteIdentical(left: File, right: File): Boolean {
    if (left.length() != right.length()) {
        return false
    }
    left.inputStream().buffered().use { leftInput ->
        right.inputStream().buffered().use { rightInput ->
            val leftBuffer = ByteArray(8192)
            val rightBuffer = ByteArray(8192)
            while (true) {
                val leftRead = leftInput.read(leftBuffer)
                val rightRead = rightInput.read(rightBuffer)
                if (leftRead != rightRead) {
                    return false
                }
                if (leftRead < 0) {
                    return true
                }
                for (index in 0 until leftRead) {
                    if (leftBuffer[index] != rightBuffer[index]) {
                        return false
                    }
                }
            }
        }
    }
}

fun writeEvidence(file: File, values: Map<String, String>) {
    file.parentFile.mkdirs()
    file.writeText(
        values.toSortedMap().entries.joinToString(
            separator = "\n",
            postfix = "\n"
        ) { (key, value) -> "$key=$value" }
    )
}

fun readEvidence(file: File): Map<String, String> {
    check(file.isFile) {
        "Evidence file does not exist: ${file.canonicalPath}"
    }
    val properties = Properties()
    file.inputStream().use(properties::load)
    return properties.stringPropertyNames().associateWith {
        properties.getProperty(it)
    }
}

fun commandBytes(
    directory: File,
    vararg command: String
): ByteArray {
    val process =
        ProcessBuilder(command.toList())
            .directory(directory)
            .redirectErrorStream(true)
            .start()
    val output =
        process.inputStream.buffered().use {
            it.readBytes()
        }
    val exitCode = process.waitFor()
    check(exitCode == 0) {
        "Command failed ($exitCode): " +
            command.joinToString(" ") +
            "\n" +
            String(output, StandardCharsets.UTF_8)
    }
    return output
}

fun commandOutput(directory: File, vararg command: String): String =
    String(
        commandBytes(directory, *command),
        StandardCharsets.UTF_8
    )

data class GitWorkspaceFingerprint(
    val commit: String,
    val dirty: Boolean,
    val statusSha256: String,
    val workspaceSha256: String,
    val pathCount: Int
)

fun splitNul(bytes: ByteArray): List<String> {
    val values = mutableListOf<String>()
    var start = 0
    for (index in bytes.indices) {
        if (bytes[index].toInt() == 0) {
            if (index > start) {
                values.add(
                    String(
                        bytes,
                        start,
                        index - start,
                        StandardCharsets.UTF_8
                    )
                )
            }
            start = index + 1
        }
    }
    if (start < bytes.size) {
        values.add(
            String(
                bytes,
                start,
                bytes.size - start,
                StandardCharsets.UTF_8
            )
        )
    }
    return values
}

fun updateLength(
    digest: MessageDigest,
    length: Long
) {
    digest.update(
        ByteBuffer.allocate(8)
            .putLong(length)
            .array()
    )
}

fun gitWorkspaceFingerprint(
    directory: File
): GitWorkspaceFingerprint {
    val root = directory.canonicalFile
    val commit =
        commandOutput(root, "git", "rev-parse", "HEAD")
            .trim()
            .lowercase()
    check(commit.matches(Regex("[0-9a-f]{40}"))) {
        "Source fingerprint requires an exact Git commit: $root"
    }
    val status =
        commandBytes(
            root,
            "git",
            "status",
            "--porcelain",
            "-z",
            "--untracked-files=all"
        )
    val listed =
        commandBytes(
            root,
            "git",
            "ls-files",
            "-z",
            "--cached",
            "--others",
            "--exclude-standard"
        )
    val ignoredReleaseInputs =
        commandBytes(
            root,
            "git",
            "ls-files",
            "-z",
            "--others",
            "--ignored",
            "--exclude-standard",
            "--",
            ".github",
            "docs",
            "gradle",
            "specifications",
            "src"
        )
    check(ignoredReleaseInputs.isEmpty()) {
        "Source fingerprint rejects ignored release inputs: " +
            splitNul(ignoredReleaseInputs).joinToString(", ")
    }
    val paths = splitNul(listed).sorted()
    check(paths.isNotEmpty()) {
        "Source fingerprint has no tracked or non-ignored files: $root"
    }
    val digest = MessageDigest.getInstance("SHA-256")
    for (relativePath in paths) {
        val pathBytes =
            relativePath.toByteArray(StandardCharsets.UTF_8)
        updateLength(digest, pathBytes.size.toLong())
        digest.update(pathBytes)
        val source = File(root, relativePath)
            .toPath()
            .toAbsolutePath()
            .normalize()
        check(source.startsWith(root.toPath())) {
            "Source path escapes checkout: $relativePath"
        }
        check(
            Files.isRegularFile(
                source,
                LinkOption.NOFOLLOW_LINKS
            )
        ) {
            "Source fingerprint rejects missing, symlink, or " +
                "non-regular path: $relativePath"
        }
        digest.update(byteArrayOf(1))
        val length = Files.size(source)
        updateLength(digest, length)
        Files.newInputStream(source).buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
    }
    return GitWorkspaceFingerprint(
        commit,
        status.isNotEmpty(),
        sha256(status),
        digest.digest().joinToString("") {
            "%02x".format(it)
        },
        paths.size
    )
}

data class FocusedLanguageArtifactEvidence(
    val coordinate: String,
    val component: String,
    val projectPath: String?,
    val includedBuildProject: Boolean,
    val file: File,
    val bytes: Long,
    val sha256: String
)

data class FocusedLanguageResolutionEvidence(
    val components: List<Map<String, Any?>>,
    val edges: List<Map<String, String>>,
    val artifacts: List<FocusedLanguageArtifactEvidence>,
    val graphSha256: String
)

fun resolveFocusedLanguageEvidence(
    configuration: Configuration,
    requiredProjectPaths: Map<String, String>,
    requireIncludedBuildProjects: Boolean
): FocusedLanguageResolutionEvidence {
    val resolution = configuration.incoming.resolutionResult
    val components =
        resolution.allComponents.map { component ->
            val identifier = component.id
            val moduleVersion = component.moduleVersion
            linkedMapOf<String, Any?>(
                "id" to identifier.displayName,
                "group" to moduleVersion?.group,
                "name" to moduleVersion?.name,
                "version" to moduleVersion?.version,
                "origin" to
                    if (identifier is ProjectComponentIdentifier) {
                        if (identifier.build.buildPath == ":") {
                            "current-build-project"
                        } else {
                            "included-build-project"
                        }
                    } else {
                        "external-module"
                    },
                "projectPath" to
                    (identifier as? ProjectComponentIdentifier)
                        ?.projectPath
            )
        }.sortedBy { it["id"].toString() }
    val edges =
        resolution.allDependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .map { dependency ->
                linkedMapOf(
                    "from" to dependency.from.id.displayName,
                    "requested" to dependency.requested.displayName,
                    "selected" to dependency.selected.id.displayName
                )
            }
            .sortedWith(
                compareBy<Map<String, String>>(
                    { it.getValue("from") },
                    { it.getValue("requested") },
                    { it.getValue("selected") }
                )
            )
    val artifacts =
        configuration.resolvedConfiguration.resolvedArtifacts
            .filter { it.extension == "jar" }
            .map { artifact ->
                val identifier = artifact.id.componentIdentifier
                val coordinate =
                    artifact.moduleVersion.id.group + ":" +
                        artifact.name + ":" +
                        artifact.moduleVersion.id.version
                FocusedLanguageArtifactEvidence(
                    coordinate = coordinate,
                    component = identifier.displayName,
                    projectPath =
                        (identifier as? ProjectComponentIdentifier)
                            ?.projectPath,
                    includedBuildProject =
                        identifier is ProjectComponentIdentifier &&
                            identifier.build.buildPath != ":",
                    file = artifact.file.canonicalFile,
                    bytes = artifact.file.length(),
                    sha256 = sha256(artifact.file)
                )
            }
            .sortedWith(
                compareBy<FocusedLanguageArtifactEvidence>(
                    { it.coordinate },
                    { it.file.name }
                )
            )
    check(artifacts.isNotEmpty()) {
        "Focused Blue Language resolution produced no JAR artifacts"
    }
    for ((moduleName, projectPath) in requiredProjectPaths) {
        val matches =
            artifacts.filter {
                it.coordinate.substringBefore(':') == "blue.language" &&
                    it.coordinate.substringAfter(':')
                        .substringBefore(':') == moduleName
            }
        check(matches.size == 1) {
            "Expected exactly one focused $moduleName JAR, found " +
                matches.joinToString { it.file.path }
        }
        if (requireIncludedBuildProjects) {
            val match = matches.single()
            check(
                match.includedBuildProject &&
                    match.projectPath == projectPath
            ) {
                "Focused module $moduleName must resolve directly from " +
                    "included-build project $projectPath, but resolved " +
                    "${match.component} (projectPath=${match.projectPath})"
            }
        }
    }
    val canonicalGraph =
        buildList {
            components.forEach {
                add(
                    "component|${it["id"]}|${it["group"]}|" +
                        "${it["name"]}|${it["version"]}|" +
                        "${it["origin"]}|${it["projectPath"]}"
                )
            }
            edges.forEach {
                add(
                    "edge|${it.getValue("from")}|" +
                        "${it.getValue("requested")}|" +
                        it.getValue("selected")
                )
            }
            artifacts.forEach {
                add(
                    "artifact|${it.coordinate}|${it.component}|" +
                        "${it.projectPath}|${it.bytes}|${it.sha256}"
                )
            }
        }.joinToString("\n", postfix = "\n")
    return FocusedLanguageResolutionEvidence(
        components = components,
        edges = edges,
        artifacts = artifacts,
        graphSha256 = sha256(
            canonicalGraph.toByteArray(StandardCharsets.UTF_8)
        )
    )
}

fun Configuration.containsBlueLanguageAggregate(): Boolean =
    incoming.resolutionResult.allComponents.any { component ->
        component.moduleVersion?.let {
            it.group == "blue.language" &&
                it.name == "blue-language-java"
        } == true
    }

fun focusedEvidenceJson(
    evidence: FocusedLanguageResolutionEvidence,
    mode: String,
    declaredCoordinates: List<String>,
    compileAggregatePresent: Boolean,
    runtimeAggregatePresent: Boolean
): Map<String, Any?> =
    linkedMapOf(
        "schema" to "blue-bex-focused-language-resolution/1.0",
        "status" to "passed",
        "mode" to mode,
        "declaredCoordinates" to declaredCoordinates,
        "graphSha256" to evidence.graphSha256,
        "componentCount" to evidence.components.size,
        "edgeCount" to evidence.edges.size,
        "artifactCount" to evidence.artifacts.size,
        "components" to evidence.components,
        "edges" to evidence.edges,
        "artifacts" to evidence.artifacts.map {
            linkedMapOf(
                "coordinate" to it.coordinate,
                "component" to it.component,
                "origin" to
                    if (it.includedBuildProject) {
                        "included-build-project"
                    } else {
                        "external-module"
                    },
                "projectPath" to it.projectPath,
                "path" to it.file.path,
                "bytes" to it.bytes,
                "sha256" to it.sha256
            )
        },
        "productionClasspaths" to
            linkedMapOf(
                "compile" to
                    linkedMapOf(
                        "aggregatePresent" to compileAggregatePresent
                    ),
                "runtime" to
                    linkedMapOf(
                        "aggregatePresent" to runtimeAggregatePresent
                    )
            )
    )

data class JavaImportInventory(
    val lineCount: Int,
    val fileCount: Int,
    val files: Map<String, Int>,
    val imports: Map<String, Int>
)

fun JavaImportInventory.toJson(): Map<String, Any?> =
    linkedMapOf(
        "lineCount" to lineCount,
        "fileCount" to fileCount,
        "files" to files.toSortedMap(),
        "imports" to imports.toSortedMap()
    )

val allBlueLanguageImportPattern =
    Regex("^import\\s+(?:static\\s+)?blue\\.language\\.")
val legacyUtilsImportPattern =
    Regex("^import\\s+(?:static\\s+)?blue\\.language\\.utils\\.")
val forbiddenLegacyImportPatterns =
    listOf(
        legacyUtilsImportPattern,
        Regex(
            "^import\\s+blue\\.language\\.snapshot\\." +
                "ResolvedSnapshot;"
        ),
        Regex("^import\\s+blue\\.language\\.NodeProvider;"),
        Regex(
            "^import\\s+blue\\.language\\.BlueOperation" +
                "(?:Limits|Outcome|Result);"
        )
    )
val allBlueLanguageImportGitPattern =
    "^import (static )?blue\\.language\\."
val legacyUtilsImportGitPattern =
    "^import (static )?blue\\.language\\.utils\\."
val forbiddenLegacyImportGitPattern =
    "^import (static )?blue\\.language\\." +
        "(utils\\.|snapshot\\.ResolvedSnapshot;|NodeProvider;|" +
        "BlueOperation(Limits|Outcome|Result);)"

fun sourceImportInventory(
    checkout: File,
    sourceRoot: String,
    matches: (String) -> Boolean
): JavaImportInventory {
    val root = File(checkout, sourceRoot)
    val lines = mutableListOf<Pair<String, String>>()
    if (root.isDirectory) {
        root.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { source ->
                source.useLines { sourceLines ->
                    sourceLines.forEach { rawLine ->
                        val line = rawLine.trim()
                        if (matches(line)) {
                            lines.add(
                                source.relativeTo(checkout)
                                    .invariantSeparatorsPath to line
                            )
                        }
                    }
                }
            }
    }
    return JavaImportInventory(
        lineCount = lines.size,
        fileCount = lines.map { it.first }.toSet().size,
        files = lines.groupingBy { it.first }.eachCount(),
        imports = lines.groupingBy { it.second }.eachCount()
    )
}

fun gitImportInventory(
    checkout: File,
    revision: String,
    sourceRoot: String,
    pattern: String
): JavaImportInventory {
    val output =
        commandOutput(
            checkout,
            "git",
            "grep",
            "-n",
            "-E",
            pattern,
            revision,
            "--",
            sourceRoot
        ).trim()
    val lines =
        if (output.isEmpty()) {
            emptyList()
        } else {
            output.lineSequence().map { rawLine ->
                val withoutRevision =
                    rawLine.removePrefix("$revision:")
                val pathSeparator = withoutRevision.indexOf(':')
                val lineSeparator =
                    withoutRevision.indexOf(':', pathSeparator + 1)
                check(pathSeparator > 0 && lineSeparator > pathSeparator) {
                    "Unexpected git grep evidence line: $rawLine"
                }
                val path = withoutRevision.substring(0, pathSeparator)
                val imported =
                    withoutRevision.substring(lineSeparator + 1).trim()
                path to imported
            }.toList()
        }
    return JavaImportInventory(
        lineCount = lines.size,
        fileCount = lines.map { it.first }.toSet().size,
        files = lines.groupingBy { it.first }.eachCount(),
        imports = lines.groupingBy { it.second }.eachCount()
    )
}

val blueLanguageFocusedResolution by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = true
    description =
        "Resolves the complete focused Blue Language component graph for " +
            "provenance, version, and JAR hash evidence."
}

val blueLanguageAggregateCompatibility by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    description =
        "Resolves the aggregate Blue Language facade only for compatibility " +
            "and provenance checks; it is not on a BEX production classpath."
}

dependencies {
    api(blueLanguageModelDeclaredCoordinate)
    api(blueLanguageCoreDeclaredCoordinate)
    implementation(blueLanguageMappingDeclaredCoordinate)
    api(blueContractsCoreDeclaredCoordinate)

    blueLanguageFocusedCoordinates.forEach { coordinate ->
        add(blueLanguageFocusedResolution.name, coordinate)
    }

    add(
        blueLanguageAggregateCompatibility.name,
        blueLanguageDeclaredCoordinate
    )

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.yaml:snakeyaml:1.31")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val blueLanguageAggregateCompatibilityEvidence =
    layout.buildDirectory.file(
        "reports/latest-language-migration/" +
            "aggregate-compatibility.properties"
    )
val blueLanguageFocusedResolutionEvidence =
    layout.buildDirectory.file(
        "reports/latest-language-migration/" +
            "focused-language-resolution.json"
    )
val writeFocusedLanguageResolutionEvidence by tasks.registering {
    group = "verification"
    description =
        "Records the complete focused Language graph, exact versions, " +
            "project origins, and JAR SHA-256 values and rejects the " +
            "aggregate facade on BEX production classpaths."
    inputs.property("dependency.mode", blueLanguageDependencyMode)
    inputs.property(
        "dependency.coordinates",
        blueLanguageFocusedCoordinates.joinToString(",")
    )
    inputs.file(latestLanguageMigrationLock)
    inputs.files(blueLanguageFocusedResolution)
    outputs.file(blueLanguageFocusedResolutionEvidence)
    outputs.upToDateWhen { false }
    doFirst {
        blueLanguageFocusedResolutionEvidence.get().asFile.delete()
    }
    doLast {
        val compileClasspath = configurations.compileClasspath.get()
        val runtimeClasspath = configurations.runtimeClasspath.get()
        val compileAggregatePresent =
            compileClasspath.containsBlueLanguageAggregate()
        val runtimeAggregatePresent =
            runtimeClasspath.containsBlueLanguageAggregate()
        check(!compileAggregatePresent && !runtimeAggregatePresent) {
            "blue-language-java is forbidden on BEX production " +
                "compile/runtime classpaths"
        }
        val evidence =
            resolveFocusedLanguageEvidence(
                blueLanguageFocusedResolution,
                blueLanguageFocusedProjectPaths,
                blueLanguageDependencyMode == "local-composite"
            )
        @Suppress("UNCHECKED_CAST")
        val lock =
            JsonSlurper().parse(latestLanguageMigrationLock.asFile)
                as Map<String, Any?>
        val languageLock = lock["language"] as? Map<*, *>
            ?: throw GradleException("Language baseline lock is malformed")
        val lockedModules =
            (languageLock["focusedModules"] as? List<*>)
                ?.map { it as Map<*, *> }
                ?: throw GradleException(
                    "Language baseline lock has no focused modules"
                )
        check(
            lockedModules.map { it["coordinate"] }.toSet() ==
                blueLanguageFocusedCoordinates.toSet()
        ) {
            "Focused dependency declarations differ from the BEX-owned lock"
        }
        if (blueLanguageDependencyMode == "local-composite") {
            for (lockedModule in lockedModules) {
                val coordinate = lockedModule["coordinate"].toString()
                val moduleName = coordinate.split(':')[1]
                val artifact =
                    evidence.artifacts.single {
                        it.coordinate.substringAfter(':')
                            .substringBefore(':') == moduleName
                    }
                check(
                    artifact.projectPath == lockedModule["projectPath"]
                ) {
                    "$moduleName resolved from ${artifact.projectPath}, " +
                        "not the locked project path " +
                        lockedModule["projectPath"]
                }
                check(
                    artifact.sha256 ==
                        lockedModule["verifiedArtifactSha256"]
                ) {
                    "$moduleName JAR differs from the verified Language " +
                        "implementation artifact: ${artifact.sha256}"
                }
            }
        }
        val output = LinkedHashMap(
            focusedEvidenceJson(
                evidence,
                blueLanguageDependencyMode,
                blueLanguageFocusedCoordinates,
                compileAggregatePresent,
                runtimeAggregatePresent
            )
        )
        output["lock"] =
            linkedMapOf(
                "path" to
                    latestLanguageMigrationLock.asFile
                        .relativeTo(projectDir)
                        .invariantSeparatorsPath,
                "sha256" to sha256(latestLanguageMigrationLock.asFile),
                "focusedModulesMatch" to true,
                "localArtifactsMatchVerifiedImplementation" to
                    (blueLanguageDependencyMode == "local-composite")
            )
        val outputFile =
            blueLanguageFocusedResolutionEvidence.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(output)) + "\n"
        )
    }
}
val latestLanguageMigrationBaselineEvidence =
    layout.buildDirectory.file(
        "reports/latest-language-migration/baseline.json"
    )
val writeLatestLanguageMigrationBaseline by tasks.registering {
    group = "verification"
    description =
        "Regenerates the migration baseline from the source-controlled " +
            "Language/BEX lock and live source inventories."
    inputs.file(latestLanguageMigrationLock)
    inputs.files(
        fileTree("src/main/java") { include("**/*.java") },
        fileTree("src/test/java") { include("**/*.java") },
        layout.projectDirectory.file(".cz.toml")
    )
    blueLanguageCompositePath?.let { path ->
        inputs.file(file(path).resolve(".cz.toml"))
    }
    outputs.file(latestLanguageMigrationBaselineEvidence)
    outputs.upToDateWhen { false }
    doFirst {
        latestLanguageMigrationBaselineEvidence.get().asFile.delete()
    }
    doLast {
        @Suppress("UNCHECKED_CAST")
        val lock =
            JsonSlurper().parse(latestLanguageMigrationLock.asFile)
                as Map<String, Any?>
        val bexLock = lock["bex"] as? Map<*, *>
            ?: throw GradleException("BEX migration lock is malformed")
        val languageLock = lock["language"] as? Map<*, *>
            ?: throw GradleException("Language migration lock is malformed")
        val migrationLock = lock["migration"] as? Map<*, *>
            ?: throw GradleException("Migration inventory lock is malformed")
        val baselineCommit =
            bexLock["migrationBaselineCommit"].toString()
        val expectedLanguageHead =
            languageLock["exactHead"].toString()
        val verifiedImplementationCommit =
            languageLock["verifiedImplementationCommit"].toString()
        val allowedLanguageDiffPaths =
            (languageLock["documentationOnlyDiffPaths"] as? List<*>)
                ?.map(Any?::toString)
                ?.sorted()
                ?: emptyList()
        val failures = mutableListOf<String>()
        fun requireBaseline(value: Boolean, failure: String) {
            if (!value) failures.add(failure)
        }
        fun expectedInventory(
            section: String,
            scope: String
        ): Pair<Int, Int> {
            val sectionMap = migrationLock[section] as? Map<*, *>
                ?: throw GradleException("Missing migration lock: $section")
            val scopeMap = sectionMap[scope] as? Map<*, *>
                ?: throw GradleException(
                    "Missing migration lock: $section.$scope"
                )
            return (
                (scopeMap["lines"] as Number).toInt() to
                    (scopeMap["files"] as Number).toInt()
                )
        }
        fun matchesExpected(
            inventory: JavaImportInventory,
            expected: Pair<Int, Int>
        ): Boolean =
            inventory.lineCount == expected.first &&
                inventory.fileCount == expected.second

        commandOutput(
            projectDir,
            "git",
            "cat-file",
            "-e",
            "$baselineCommit^{commit}"
        )
        val bexFingerprint = gitWorkspaceFingerprint(projectDir)
        val bexCzToml = file(".cz.toml")
        val actualBexCzTomlSha256 = sha256(bexCzToml)
        requireBaseline(
            actualBexCzTomlSha256 == bexLock["czTomlSha256"],
            "bex-cz-toml-differs-from-lock"
        )

        val languageDirectory =
            blueLanguageCompositePath
                ?.let { file(it).canonicalFile }
        val languageFingerprint =
            languageDirectory
                ?.takeIf(File::isDirectory)
                ?.let(::gitWorkspaceFingerprint)
        requireBaseline(
            languageFingerprint != null,
            "language-composite-checkout-unavailable"
        )
        val actualLanguageCzTomlSha256 =
            languageDirectory
                ?.resolve(".cz.toml")
                ?.takeIf(File::isFile)
                ?.let(::sha256)
        requireBaseline(
            actualLanguageCzTomlSha256 ==
                languageLock["czTomlSha256"],
            "language-cz-toml-differs-from-lock"
        )
        requireBaseline(
            languageFingerprint?.commit == expectedLanguageHead,
            "language-head-differs-from-lock"
        )
        requireBaseline(
            languageFingerprint != null && !languageFingerprint.dirty,
            "language-git-worktree-dirty"
        )
        val changedLanguagePaths =
            languageDirectory?.let {
                commandOutput(
                    it,
                    "git",
                    "diff",
                    "--name-only",
                    "$verifiedImplementationCommit..$expectedLanguageHead"
                ).lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .sorted()
                    .toList()
            } ?: emptyList()
        val codeEquivalent =
            languageFingerprint?.commit == expectedLanguageHead &&
                changedLanguagePaths == allowedLanguageDiffPaths &&
                actualLanguageCzTomlSha256 ==
                languageLock["czTomlSha256"]
        requireBaseline(
            codeEquivalent,
            "language-head-not-code-equivalent-to-verified-implementation"
        )

        val baselineProductionImports =
            gitImportInventory(
                projectDir,
                baselineCommit,
                "src/main/java",
                allBlueLanguageImportGitPattern
            )
        val baselineTestImports =
            gitImportInventory(
                projectDir,
                baselineCommit,
                "src/test/java",
                allBlueLanguageImportGitPattern
            )
        val baselineProductionUtils =
            gitImportInventory(
                projectDir,
                baselineCommit,
                "src/main/java",
                legacyUtilsImportGitPattern
            )
        val baselineTestUtils =
            gitImportInventory(
                projectDir,
                baselineCommit,
                "src/test/java",
                legacyUtilsImportGitPattern
            )
        val baselineProductionForbidden =
            gitImportInventory(
                projectDir,
                baselineCommit,
                "src/main/java",
                forbiddenLegacyImportGitPattern
            )
        val baselineTestForbidden =
            gitImportInventory(
                projectDir,
                baselineCommit,
                "src/test/java",
                forbiddenLegacyImportGitPattern
            )
        requireBaseline(
            matchesExpected(
                baselineProductionImports,
                expectedInventory(
                    "languageImportInventory",
                    "production"
                )
            ) && matchesExpected(
                baselineTestImports,
                expectedInventory("languageImportInventory", "test")
            ),
            "baseline-language-import-inventory-differs-from-lock"
        )
        requireBaseline(
            matchesExpected(
                baselineProductionUtils,
                expectedInventory("legacyUtilsImports", "production")
            ) && matchesExpected(
                baselineTestUtils,
                expectedInventory("legacyUtilsImports", "test")
            ),
            "baseline-utils-import-ledger-differs-from-lock"
        )
        requireBaseline(
            matchesExpected(
                baselineProductionForbidden,
                expectedInventory(
                    "allForbiddenLegacyImports",
                    "production"
                )
            ) && matchesExpected(
                baselineTestForbidden,
                expectedInventory(
                    "allForbiddenLegacyImports",
                    "test"
                )
            ),
            "baseline-forbidden-import-ledger-differs-from-lock"
        )

        val currentProductionImports =
            sourceImportInventory(
                projectDir,
                "src/main/java"
            ) { allBlueLanguageImportPattern.containsMatchIn(it) }
        val currentTestImports =
            sourceImportInventory(
                projectDir,
                "src/test/java"
            ) { allBlueLanguageImportPattern.containsMatchIn(it) }
        val currentProductionForbidden =
            sourceImportInventory(
                projectDir,
                "src/main/java"
            ) { line ->
                forbiddenLegacyImportPatterns.any {
                    it.containsMatchIn(line)
                }
            }
        val currentTestForbidden =
            sourceImportInventory(
                projectDir,
                "src/test/java"
            ) { line ->
                forbiddenLegacyImportPatterns.any {
                    it.containsMatchIn(line)
                }
            }

        val output =
            linkedMapOf<String, Any?>(
                "schema" to
                    "blue-bex-latest-language-migration-baseline/1.0",
                "status" to
                    if (failures.isEmpty()) "passed" else "failed",
                "failures" to failures,
                "lock" to
                    linkedMapOf(
                        "path" to
                            latestLanguageMigrationLock.asFile
                                .relativeTo(projectDir)
                                .invariantSeparatorsPath,
                        "sha256" to
                            sha256(latestLanguageMigrationLock.asFile)
                    ),
                "toolchain" to
                    linkedMapOf(
                        "gradle" to gradle.gradleVersion,
                        "java" to System.getProperty("java.version"),
                        "os" to System.getProperty("os.name"),
                        "architecture" to System.getProperty("os.arch")
                    ),
                "bex" to
                    linkedMapOf(
                        "baselineCommit" to baselineCommit,
                        "currentCommit" to bexFingerprint.commit,
                        "gitWorktreeDirty" to bexFingerprint.dirty,
                        "workspaceSha256" to
                            bexFingerprint.workspaceSha256,
                        "czToml" to
                            linkedMapOf(
                                "expectedSha256" to
                                    bexLock["czTomlSha256"],
                                "actualSha256" to
                                    actualBexCzTomlSha256,
                                "matches" to
                                    (actualBexCzTomlSha256 ==
                                        bexLock["czTomlSha256"])
                            )
                    ),
                "language" to
                    linkedMapOf(
                        "path" to languageDirectory?.path,
                        "exactCommit" to languageFingerprint?.commit,
                        "gitWorktreeDirty" to languageFingerprint?.dirty,
                        "includedBuildCleanClaim" to "not-made",
                        "verifiedImplementationCommit" to
                            verifiedImplementationCommit,
                        "changedPathsSinceVerifiedImplementation" to
                            changedLanguagePaths,
                        "allowedDocumentationOnlyPaths" to
                            allowedLanguageDiffPaths,
                        "codeEquivalent" to codeEquivalent,
                        "czToml" to
                            linkedMapOf(
                                "expectedSha256" to
                                    languageLock["czTomlSha256"],
                                "actualSha256" to
                                    actualLanguageCzTomlSha256,
                                "matches" to
                                    (actualLanguageCzTomlSha256 ==
                                        languageLock["czTomlSha256"])
                            ),
                        "focusedModules" to
                            languageLock["focusedModules"],
                        "hostingPackageIdentities" to
                            languageLock["hostingPackageIdentities"]
                    ),
                "sourceApiInventory" to
                    linkedMapOf(
                        "baseline" to
                            linkedMapOf(
                                "revision" to baselineCommit,
                                "production" to
                                    baselineProductionImports.toJson(),
                                "test" to baselineTestImports.toJson()
                            ),
                        "current" to
                            linkedMapOf(
                                "production" to
                                    currentProductionImports.toJson(),
                                "test" to currentTestImports.toJson()
                            )
                    ),
                "migrationLedger" to
                    linkedMapOf(
                        "legacyUtils" to
                            linkedMapOf(
                                "before" to
                                    linkedMapOf(
                                        "production" to
                                            baselineProductionUtils.toJson(),
                                        "test" to
                                            baselineTestUtils.toJson()
                                    ),
                                "after" to
                                    linkedMapOf(
                                        "production" to
                                            sourceImportInventory(
                                                projectDir,
                                                "src/main/java"
                                            ) {
                                                legacyUtilsImportPattern
                                                    .containsMatchIn(it)
                                            }.toJson(),
                                        "test" to
                                            sourceImportInventory(
                                                projectDir,
                                                "src/test/java"
                                            ) {
                                                legacyUtilsImportPattern
                                                    .containsMatchIn(it)
                                            }.toJson()
                                    )
                            ),
                        "allForbiddenLegacyImports" to
                            linkedMapOf(
                                "before" to
                                    linkedMapOf(
                                        "production" to
                                            baselineProductionForbidden
                                                .toJson(),
                                        "test" to
                                            baselineTestForbidden.toJson()
                                    ),
                                "after" to
                                    linkedMapOf(
                                        "production" to
                                            currentProductionForbidden
                                                .toJson(),
                                        "test" to
                                            currentTestForbidden.toJson()
                                    )
                            )
                    )
            )
        val outputFile =
            latestLanguageMigrationBaselineEvidence.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(output)) + "\n"
        )
    }
}
val verifyBlueLanguageAggregateCompatibility by tasks.registering {
    group = "verification"
    description =
        "Resolves and inspects the aggregate Blue Language facade without " +
            "adding it to a BEX production classpath."
    inputs.property("dependency.mode", blueLanguageDependencyMode)
    inputs.property(
        "dependency.coordinate",
        blueLanguageDeclaredCoordinate
    )
    inputs.files(blueLanguageAggregateCompatibility)
    outputs.file(blueLanguageAggregateCompatibilityEvidence)
    outputs.upToDateWhen { false }
    doFirst {
        blueLanguageAggregateCompatibilityEvidence.get().asFile.delete()
    }
    doLast {
        val matches =
            blueLanguageAggregateCompatibility
                .resolvedConfiguration
                .resolvedArtifacts
                .filter {
                    it.moduleVersion.id.group == "blue.language" &&
                        it.name == "blue-language-java" &&
                        it.extension == "jar"
                }
        check(matches.size == 1) {
            "Expected exactly one aggregate blue-language-java artifact, " +
                "found " +
                matches.joinToString { it.file.absolutePath }
        }
        val artifact = matches.single()
        check(artifact.file.isFile && artifact.file.length() > 0L) {
            "Aggregate Blue Language artifact is missing or empty: " +
                artifact.file
        }
        ZipFile(artifact.file).use { archive ->
            check(archive.getEntry("blue/language/Blue.class") != null) {
                "Aggregate Blue Language artifact does not expose the " +
                    "compatibility facade blue.language.Blue: " +
                    artifact.file
            }
        }
        val component = artifact.id.componentIdentifier
        if (blueLanguageDependencyMode == "local-composite") {
            val projectComponent =
                component as? ProjectComponentIdentifier
            check(
                projectComponent != null &&
                    projectComponent.build.buildPath != ":" &&
                    projectComponent.projectPath ==
                    ":blue-language-java"
            ) {
                "Local aggregate compatibility artifact did not resolve " +
                    "from :blue-language-java: " +
                    component.displayName
            }
        }
        writeEvidence(
            blueLanguageAggregateCompatibilityEvidence.get().asFile,
            mapOf(
                "schema" to
                    "blue-bex-language-aggregate-compatibility/1.0",
                "status" to "passed",
                "mode" to blueLanguageDependencyMode,
                "declared.coordinate" to
                    blueLanguageDeclaredCoordinate,
                "effective.component" to component.displayName,
                "effective.coordinate" to
                    (
                        artifact.moduleVersion.id.group +
                            ":" + artifact.name + ":" +
                            artifact.moduleVersion.id.version
                    ),
                "artifact.path" to artifact.file.canonicalPath,
                "artifact.bytes" to artifact.file.length().toString(),
                "artifact.sha256" to sha256(artifact.file)
            )
        )
    }
}

tasks.test {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    )
    useJUnitPlatform()
    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }
    testLogging {
        events("PASSED", "FAILED", "SKIPPED")
        showStandardStreams = true
    }
}

val mainJar = tasks.named<Jar>("jar")
val sourcesJarTask = tasks.named<Jar>("sourcesJar")
val javadocJarTask = tasks.named<Jar>("javadocJar")
val javadocTask = tasks.named<Javadoc>("javadoc")
val sourceReleaseIncludes =
    listOf(
        ".cz.toml",
        ".github/**",
        "LICENSE",
        "README.md",
        "build.gradle.kts",
        "docs/**",
        "gradle.properties",
        "gradle/**",
        "gradlew",
        "gradlew.bat",
        "settings.gradle.kts",
        "specifications/**",
        "src/**"
    )
val sourceReleaseExcludes =
    listOf(
        ".git/**",
        ".gradle/**",
        ".idea/**",
        "build/**",
        "out/**",
        "target/**",
        "work-status.txt",
        "*.zip",
        "*.tar",
        "*.tar.gz",
        "*.tgz",
        "*.7z",
        "**/*.zip",
        "**/*.tar",
        "**/*.tar.gz",
        "**/*.tgz",
        "**/*.7z",
        "**/*.class",
        "**/*.log",
        "**/*.tmp",
        "**/*.bak",
        "**/*.swp",
        "**/*~",
        ".DS_Store",
        "**/.DS_Store"
    )
val sourceReleaseInputs =
    fileTree(projectDir) {
        include(sourceReleaseIncludes)
        exclude(sourceReleaseExcludes)
    }
val sourceReleaseRoot =
    "${base.archivesName.get()}-${project.version}"
val sourceReleaseArchive by tasks.registering(Zip::class) {
    group = "distribution"
    description =
        "Assembles the reproducible BEX source release from release inputs."
    archiveBaseName.set(base.archivesName)
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("source-release")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(sourceReleaseInputs) {
        exclude(
            "gradlew",
            ".github/scripts/run-final-publication-gates.sh"
        )
        into(sourceReleaseRoot)
    }
    from("gradlew") {
        into(sourceReleaseRoot)
        filePermissions {
            unix("rwxr-xr-x")
        }
    }
    from(".github/scripts/run-final-publication-gates.sh") {
        into("$sourceReleaseRoot/.github/scripts")
        filePermissions {
            unix("rwxr-xr-x")
        }
    }
}
val rebuiltSourceReleaseArchive by tasks.registering(Zip::class) {
    group = "verification"
    description =
        "Independently reassembles the source release from the same working-tree inputs."
    archiveFileName.set(sourceReleaseArchive.flatMap { it.archiveFileName })
    destinationDirectory.set(
        layout.buildDirectory.dir("reproducibility/source-release")
    )
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(sourceReleaseInputs) {
        exclude(
            "gradlew",
            ".github/scripts/run-final-publication-gates.sh"
        )
        into(sourceReleaseRoot)
    }
    from("gradlew") {
        into(sourceReleaseRoot)
        filePermissions {
            unix("rwxr-xr-x")
        }
    }
    from(".github/scripts/run-final-publication-gates.sh") {
        into("$sourceReleaseRoot/.github/scripts")
        filePermissions {
            unix("rwxr-xr-x")
        }
    }
}
val rebuiltMainJar by tasks.registering(Jar::class) {
    group = "verification"
    description =
        "Repackages the current compiled main output for archive byte comparison."
    archiveFileName.set(mainJar.flatMap { it.archiveFileName })
    destinationDirectory.set(
        layout.buildDirectory.dir("reproducibility/main")
    )
    from(sourceSets.main.get().output)
}
val rebuiltSourcesJar by tasks.registering(Jar::class) {
    group = "verification"
    description =
        "Repackages the current source inputs for archive byte comparison."
    archiveFileName.set(sourcesJarTask.flatMap { it.archiveFileName })
    destinationDirectory.set(
        layout.buildDirectory.dir("reproducibility/sources")
    )
    from(sourceSets.main.get().allSource)
}
val rebuiltJavadoc by tasks.registering(Javadoc::class) {
    group = "verification"
    description =
        "Freshly regenerates the public Javadoc for deterministic comparison."
    source = sourceSets.main.get().allJava
    classpath = sourceSets.main.get().compileClasspath
    destinationDir =
        layout.buildDirectory.dir(
            "reproducibility/javadoc-content"
        ).get().asFile
}
val rebuiltJavadocJar by tasks.registering(Jar::class) {
    group = "verification"
    description =
        "Packages freshly regenerated Javadoc for byte comparison."
    dependsOn(rebuiltJavadoc)
    archiveFileName.set(javadocJarTask.flatMap { it.archiveFileName })
    destinationDirectory.set(
        layout.buildDirectory.dir("reproducibility/javadoc")
    )
    from(rebuiltJavadoc.map { it.destinationDir })
}

val deterministicArchiveEvidence =
    layout.buildDirectory.file(
        "reports/bex-release/deterministic-archives.properties"
    )
val sourceReleaseEvidence =
    layout.buildDirectory.file(
        "reports/bex-release/source-release.properties"
    )
val sourceReleaseChecksum =
    sourceReleaseArchive.flatMap { archive ->
        archive.archiveFile.map { file ->
            File(file.asFile.parentFile, "${file.asFile.name}.sha256")
        }
    }
val verifyDeterministicArchives by tasks.registering {
    group = "verification"
    description =
        "Checks JAR packaging determinism and independently reassembles the source release from the same working tree; this is not an independent clean compilation or checkout."
    dependsOn(
        mainJar,
        sourcesJarTask,
        javadocJarTask,
        javadocTask,
        rebuiltMainJar,
        rebuiltSourcesJar,
        rebuiltJavadocJar,
        sourceReleaseArchive,
        rebuiltSourceReleaseArchive
    )
    outputs.file(deterministicArchiveEvidence)
    outputs.file(sourceReleaseEvidence)
    outputs.file(sourceReleaseChecksum)
    outputs.upToDateWhen { false }
    doFirst {
        deterministicArchiveEvidence.get().asFile.delete()
        sourceReleaseEvidence.get().asFile.delete()
        sourceReleaseChecksum.get().delete()
    }
    doLast {
        val originalMain = mainJar.get().archiveFile.get().asFile
        val rebuiltMain = rebuiltMainJar.get().archiveFile.get().asFile
        val originalSources =
            sourcesJarTask.get().archiveFile.get().asFile
        val rebuiltSources =
            rebuiltSourcesJar.get().archiveFile.get().asFile
        val originalJavadoc =
            javadocJarTask.get().archiveFile.get().asFile
        val regeneratedJavadoc =
            rebuiltJavadocJar.get().archiveFile.get().asFile
        val originalSourceRelease =
            sourceReleaseArchive.get().archiveFile.get().asFile
        val rebuiltSourceRelease =
            rebuiltSourceReleaseArchive.get().archiveFile.get().asFile
        val originalMainHash = sha256(originalMain)
        val rebuiltMainHash = sha256(rebuiltMain)
        val originalSourcesHash = sha256(originalSources)
        val rebuiltSourcesHash = sha256(rebuiltSources)
        val originalJavadocHash = sha256(originalJavadoc)
        val regeneratedJavadocHash = sha256(regeneratedJavadoc)
        val originalSourceReleaseHash = sha256(originalSourceRelease)
        val rebuiltSourceReleaseHash = sha256(rebuiltSourceRelease)
        val sourceReleaseByteIdentity =
            byteIdentical(originalSourceRelease, rebuiltSourceRelease)
        val originalExecutableModes =
            sourceReleaseExecutableModes(
                originalSourceRelease,
                sourceReleaseRoot
            )
        val rebuiltExecutableModes =
            sourceReleaseExecutableModes(
                rebuiltSourceRelease,
                sourceReleaseRoot
            )
        check(originalMainHash == rebuiltMainHash) {
            "Main JAR rebuild differs: $originalMainHash != $rebuiltMainHash"
        }
        check(originalSourcesHash == rebuiltSourcesHash) {
            "Source JAR rebuild differs: $originalSourcesHash != $rebuiltSourcesHash"
        }
        check(originalJavadocHash == regeneratedJavadocHash) {
            "Javadoc JAR rebuild differs: " +
                "$originalJavadocHash != $regeneratedJavadocHash"
        }
        check(sourceReleaseByteIdentity) {
            "Source-release ZIP replica is not byte-identical to the original"
        }
        check(originalSourceReleaseHash == rebuiltSourceReleaseHash) {
            "Source-release ZIP replica differs: " +
                "$originalSourceReleaseHash != $rebuiltSourceReleaseHash"
        }
        check(originalExecutableModes == rebuiltExecutableModes) {
            "Source-release executable modes differ between assemblies"
        }
        sourceReleaseChecksum.get().writeText(
            "$originalSourceReleaseHash  ${originalSourceRelease.name}\n"
        )
        writeEvidence(
            sourceReleaseEvidence.get().asFile,
            mapOf(
                "archive.bytes" to
                    originalSourceRelease.length().toString(),
                "archive.path" to
                    originalSourceRelease
                        .relativeTo(projectDir)
                        .invariantSeparatorsPath,
                "archive.sha256" to originalSourceReleaseHash,
                "assembly" to
                    "two-independent-gradle-zip-tasks",
                "byteIdentity" to sourceReleaseByteIdentity.toString(),
                "checksum.path" to
                    sourceReleaseChecksum.get()
                        .relativeTo(projectDir)
                        .invariantSeparatorsPath,
                "checksum.sha256" to
                    sha256(sourceReleaseChecksum.get()),
                "excluded.patterns" to
                    sourceReleaseExcludes.joinToString(","),
                "hashIdentity" to
                    (originalSourceReleaseHash ==
                        rebuiltSourceReleaseHash).toString(),
                "included.patterns" to
                    sourceReleaseIncludes.joinToString(","),
                "independentCleanCheckout" to "false",
                "executable.gradlew.mode" to
                    originalExecutableModes.getValue("gradlew"),
                "executable.publicationGate.mode" to
                    originalExecutableModes.getValue(
                        ".github/scripts/run-final-publication-gates.sh"
                    ),
                "replica.bytes" to
                    rebuiltSourceRelease.length().toString(),
                "replica.path" to
                    rebuiltSourceRelease
                        .relativeTo(projectDir)
                        .invariantSeparatorsPath,
                "replica.sha256" to rebuiltSourceReleaseHash,
                "rootDirectory" to sourceReleaseRoot,
                "schema" to
                    "blue-bex-source-release-evidence/1.0",
                "scope" to
                    "independent-archive-assembly-from-the-same-working-tree-inputs",
                "status" to "passed"
            )
        )
        writeEvidence(
            deterministicArchiveEvidence.get().asFile,
            mapOf(
                "main.original.path" to
                    originalMain.relativeTo(projectDir).invariantSeparatorsPath,
                "main.original.sha256" to originalMainHash,
                "main.rebuild.path" to
                    rebuiltMain.relativeTo(projectDir).invariantSeparatorsPath,
                "main.rebuild.sha256" to rebuiltMainHash,
                "sources.original.path" to
                    originalSources.relativeTo(projectDir).invariantSeparatorsPath,
                "sources.original.sha256" to originalSourcesHash,
                "sources.rebuild.path" to
                    rebuiltSources.relativeTo(projectDir).invariantSeparatorsPath,
                "sources.rebuild.sha256" to rebuiltSourcesHash,
                "javadoc.original.path" to
                    originalJavadoc.relativeTo(projectDir).invariantSeparatorsPath,
                "javadoc.original.sha256" to originalJavadocHash,
                "javadoc.rebuild.path" to
                    regeneratedJavadoc.relativeTo(projectDir).invariantSeparatorsPath,
                "javadoc.rebuild.sha256" to regeneratedJavadocHash,
                "javadoc.freshlyRegenerated" to "true",
                "sourceRelease.original.path" to
                    originalSourceRelease
                        .relativeTo(projectDir)
                        .invariantSeparatorsPath,
                "sourceRelease.original.sha256" to
                    originalSourceReleaseHash,
                "sourceRelease.replica.path" to
                    rebuiltSourceRelease
                        .relativeTo(projectDir)
                        .invariantSeparatorsPath,
                "sourceRelease.replica.sha256" to
                    rebuiltSourceReleaseHash,
                "sourceRelease.byteIdentity" to
                    sourceReleaseByteIdentity.toString(),
                "sourceRelease.hashIdentity" to
                    (originalSourceReleaseHash ==
                        rebuiltSourceReleaseHash).toString(),
                "sourceRelease.independentAssembly" to "true",
                "sourceRelease.independentCleanCheckout" to "false",
                "scope" to
                    "jar-packaging-determinism-and-source-release-reassembly-from-the-same-working-tree",
                "independentCleanCompilation" to "false",
                "status" to "passed"
            )
        )
    }
}

val cleanBuildArtifactEvidence =
    layout.buildDirectory.file(
        "reports/bex-release/clean-build-artifacts.properties"
    )
val cleanBuildDependencyArtifact =
    layout.buildDirectory.file(
        "reports/bex-release/clean-build-inputs/" +
            "blue-language-java.jar"
    )
val cleanBuildFocusedDependencyDirectory =
    layout.buildDirectory.dir(
        "reports/bex-release/clean-build-inputs/focused-language"
    )
val invalidateCleanBuildArtifactEvidence by tasks.registering {
    group = "verification"
    description =
        "Invalidates any prior clean-build receipt before artifact work starts."
    outputs.upToDateWhen { false }
    doLast {
        cleanBuildArtifactEvidence.get().asFile.delete()
        cleanBuildDependencyArtifact.get().asFile.delete()
        project.delete(cleanBuildFocusedDependencyDirectory)
    }
}
listOf(
    mainJar,
    sourcesJarTask,
    javadocJarTask,
    sourceReleaseArchive
).forEach { artifactTask ->
    artifactTask.configure {
        mustRunAfter(invalidateCleanBuildArtifactEvidence)
    }
}
verifyBlueLanguageAggregateCompatibility {
    mustRunAfter(invalidateCleanBuildArtifactEvidence)
}
writeFocusedLanguageResolutionEvidence {
    mustRunAfter(invalidateCleanBuildArtifactEvidence)
}
val writeCleanBuildArtifactHashes by tasks.registering {
    group = "verification"
    description =
        "Records all four release hashes from one clean committed checkout."
    dependsOn(
        invalidateCleanBuildArtifactEvidence,
        verifyBlueLanguageAggregateCompatibility,
        writeFocusedLanguageResolutionEvidence,
        mainJar,
        sourcesJarTask,
        javadocJarTask,
        sourceReleaseArchive
    )
    outputs.file(cleanBuildArtifactEvidence)
    outputs.file(cleanBuildDependencyArtifact)
    outputs.dir(cleanBuildFocusedDependencyDirectory)
    outputs.upToDateWhen { false }
    doFirst {
        cleanBuildArtifactEvidence.get().asFile.delete()
        cleanBuildDependencyArtifact.get().asFile.delete()
        project.delete(cleanBuildFocusedDependencyDirectory)
    }
    doLast {
        val checkout =
            gitWorkspaceFingerprint(projectDir)
        check(!checkout.dirty) {
            "Clean-build evidence requires a completely clean checkout:\n" +
                commandOutput(
                    projectDir,
                    "git",
                    "status",
                    "--short"
                )
        }
        val gitDirectory =
            commandOutput(
                projectDir,
                "git",
                "rev-parse",
                "--absolute-git-dir"
            ).trim()
        val languageArtifacts =
            blueLanguageAggregateCompatibility
                .resolvedConfiguration
                .resolvedArtifacts
                .filter {
                    it.moduleVersion.id.group == "blue.language" &&
                        it.name == "blue-language-java" &&
                        it.extension == "jar"
                }
        check(languageArtifacts.size == 1) {
            "Expected exactly one Blue Language dependency artifact"
        }
        val languageArtifact = languageArtifacts.single()
        val languageArtifactCopy =
            cleanBuildDependencyArtifact.get().asFile
        languageArtifactCopy.parentFile.mkdirs()
        languageArtifact.file.copyTo(
            languageArtifactCopy,
            overwrite = true
        )
        check(
            languageArtifactCopy.isFile &&
                languageArtifactCopy.length() ==
                languageArtifact.file.length() &&
                sha256(languageArtifactCopy) ==
                sha256(languageArtifact.file)
        ) {
            "Failed to preserve the exact Blue Language dependency " +
                "artifact with the clean-build receipt"
        }
        val focusedResolution =
            resolveFocusedLanguageEvidence(
                blueLanguageFocusedResolution,
                blueLanguageFocusedProjectPaths,
                blueLanguageDependencyMode == "local-composite"
            )
        val focusedCopyDirectory =
            cleanBuildFocusedDependencyDirectory.get().asFile
        focusedCopyDirectory.mkdirs()
        val focusedArtifactCopies =
            focusedResolution.artifacts.mapIndexed { index, artifact ->
                val safeCoordinate =
                    artifact.coordinate.replace(
                        Regex("[^A-Za-z0-9._-]"),
                        "_"
                    )
                val copy =
                    File(
                        focusedCopyDirectory,
                        "%03d-%s.jar".format(index, safeCoordinate)
                    )
                artifact.file.copyTo(copy, overwrite = true)
                check(
                    copy.length() == artifact.bytes &&
                        sha256(copy) == artifact.sha256
                ) {
                    "Failed to preserve focused dependency artifact " +
                        artifact.coordinate
                }
                artifact to copy
            }
        val compositeDirectory =
            blueLanguageCompositePath
                ?.let { file(it).canonicalFile }
        val compositeFingerprint =
            compositeDirectory
                ?.let(::gitWorkspaceFingerprint)
        if (blueLanguageDependencyMode == "local-composite") {
            check(compositeDirectory != null) {
                "Local-composite clean-build evidence requires a source path"
            }
            check(compositeFingerprint != null) {
                "Local-composite source fingerprint is unavailable"
            }
            check(!compositeFingerprint.dirty) {
                "Local-composite clean-build evidence rejects a dirty " +
                    "Blue Language checkout"
            }
        }
        val artifacts =
            mapOf(
                "main" to mainJar.get().archiveFile.get().asFile,
                "sources" to
                    sourcesJarTask.get().archiveFile.get().asFile,
                "javadoc" to
                    javadocJarTask.get().archiveFile.get().asFile,
                "sourceRelease" to
                    sourceReleaseArchive.get().archiveFile.get().asFile
            )
        val values =
            linkedMapOf(
                "schema" to
                    "blue-bex-clean-build-artifacts/1.1",
                "status" to "passed",
                "commit" to checkout.commit,
                "checkout.clean" to "true",
                "checkout.root" to projectDir.canonicalPath,
                "checkout.gitDirectory" to
                    File(gitDirectory).canonicalPath,
                "checkout.gitStatusSha256" to
                    checkout.statusSha256,
                "checkout.workspaceSha256" to
                    checkout.workspaceSha256,
                "checkout.pathCount" to
                    checkout.pathCount.toString(),
                "project.version" to project.version.toString(),
                "dependency.mode" to blueLanguageDependencyMode,
                "dependency.coordinate" to
                    blueLanguageDeclaredCoordinate,
                "dependency.effectiveCoordinate" to
                    (
                        languageArtifact.moduleVersion.id.group +
                            ":" +
                            languageArtifact.name +
                            ":" +
                            languageArtifact.moduleVersion.id.version
                    ),
                "dependency.artifact.bytes" to
                    languageArtifactCopy.length().toString(),
                "dependency.artifact.path" to
                    languageArtifactCopy.relativeTo(projectDir)
                        .invariantSeparatorsPath,
                "dependency.artifact.sha256" to
                    sha256(languageArtifactCopy),
                "dependency.aggregateCompatibilityOnly" to "true",
                "dependency.focused.declaredCoordinates" to
                    blueLanguageFocusedCoordinates.joinToString(","),
                "dependency.focused.graphSha256" to
                    focusedResolution.graphSha256,
                "dependency.focused.componentCount" to
                    focusedResolution.components.size.toString(),
                "dependency.focused.edgeCount" to
                    focusedResolution.edges.size.toString(),
                "dependency.focused.artifactCount" to
                    focusedArtifactCopies.size.toString(),
                "composite.path" to
                    (compositeDirectory?.path ?: ""),
                "composite.commit" to
                    (compositeFingerprint?.commit ?: ""),
                "composite.dirty" to
                    (compositeFingerprint?.dirty
                        ?.toString()
                        ?: "false"),
                "composite.gitStatusSha256" to
                    (
                        compositeFingerprint
                            ?.statusSha256
                            ?: ""
                    ),
                "composite.workspaceSha256" to
                    (
                        compositeFingerprint
                            ?.workspaceSha256
                            ?: ""
                    ),
                "composite.pathCount" to
                    (
                        compositeFingerprint
                            ?.pathCount
                            ?.toString()
                            ?: "0"
                    )
            )
        focusedArtifactCopies.forEachIndexed { index, pair ->
            val (artifact, copy) = pair
            val prefix =
                "dependency.focused.artifact.%03d".format(index)
            values["$prefix.coordinate"] = artifact.coordinate
            values["$prefix.component"] = artifact.component
            values["$prefix.projectPath"] =
                artifact.projectPath.orEmpty()
            values["$prefix.origin"] =
                if (artifact.includedBuildProject) {
                    "included-build-project"
                } else {
                    "external-module"
                }
            values["$prefix.path"] =
                copy.relativeTo(projectDir).invariantSeparatorsPath
            values["$prefix.bytes"] = copy.length().toString()
            values["$prefix.sha256"] = sha256(copy)
        }
        for ((name, artifact) in artifacts) {
            values["artifact.$name.path"] =
                artifact.relativeTo(projectDir)
                    .invariantSeparatorsPath
            values["artifact.$name.bytes"] =
                artifact.length().toString()
            values["artifact.$name.sha256"] =
                sha256(artifact)
        }
        writeEvidence(
            cleanBuildArtifactEvidence.get().asFile,
            values
        )
    }
}

val independentCleanBuildEvidence =
    layout.projectDirectory.file(
        ".gradle/bex-hosted-release/" +
            "independent-clean-builds-" +
            "$blueLanguageDependencyMode.properties"
    )
val verifyIndependentCleanBuildReproducibility by tasks.registering {
    group = "verification"
    description =
        "Compares main, sources, Javadoc, and source-release hashes from two clean checkouts of the same commit."
    dependsOn(
        verifyBlueLanguageAggregateCompatibility,
        writeFocusedLanguageResolutionEvidence
    )
    val firstEvidencePath =
        providers.gradleProperty("cleanBuildEvidenceOne")
    val secondEvidencePath =
        providers.gradleProperty("cleanBuildEvidenceTwo")
    inputs.property(
        "cleanBuildEvidenceOne",
        firstEvidencePath.orElse("")
    )
    inputs.property(
        "cleanBuildEvidenceTwo",
        secondEvidencePath.orElse("")
    )
    outputs.file(independentCleanBuildEvidence)
    outputs.upToDateWhen { false }
    doFirst {
        independentCleanBuildEvidence.asFile.delete()
    }
    doLast {
        val firstPath =
            firstEvidencePath.orNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::file)
                ?: throw GradleException(
                    "-PcleanBuildEvidenceOne is required"
                )
        val secondPath =
            secondEvidencePath.orNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::file)
                ?: throw GradleException(
                    "-PcleanBuildEvidenceTwo is required"
                )
        check(
            firstPath.canonicalFile !=
                secondPath.canonicalFile
        ) {
            "Independent-build evidence files must be distinct"
        }
        val first = readEvidence(firstPath)
        val second = readEvidence(secondPath)
        val currentCommit =
            commandOutput(
                projectDir,
                "git",
                "rev-parse",
                "HEAD"
            ).trim().lowercase()
        val expectedSchema =
            "blue-bex-clean-build-artifacts/1.1"
        val artifactNames =
            listOf(
                "main",
                "sources",
                "javadoc",
                "sourceRelease"
            )
        val artifactPrefix =
            "blue-bex-java-${project.version}"
        val expectedArtifactPaths =
            mapOf(
                "main" to
                    "build/libs/$artifactPrefix.jar",
                "sources" to
                    "build/libs/$artifactPrefix-sources.jar",
                "javadoc" to
                    "build/libs/$artifactPrefix-javadoc.jar",
                "sourceRelease" to
                    "build/distributions/" +
                    "$artifactPrefix-source-release.zip"
            )
        val expectedDependencyArtifactPath =
            "build/reports/bex-release/clean-build-inputs/" +
                "blue-language-java.jar"
        val authenticatedRoots =
            linkedMapOf<String, File>()
        val authenticatedGitDirectories =
            linkedMapOf<String, File>()
        val authenticatedFingerprints =
            linkedMapOf<String, GitWorkspaceFingerprint>()
        val authenticatedArtifacts =
            linkedMapOf<
                String,
                Map<String, Map<String, String>>
            >()
        val authenticatedDependencyArtifacts =
            linkedMapOf<String, Map<String, String>>()
        val authenticatedFocusedArtifacts =
            linkedMapOf<String, List<Map<String, String>>>()
        val authenticatedFocusedGraphHashes =
            linkedMapOf<String, String>()
        for ((label, evidence) in
            listOf("first" to first, "second" to second)) {
            check(evidence["schema"] == expectedSchema) {
                "$label clean-build evidence has the wrong schema"
            }
            check(evidence["status"] == "passed") {
                "$label clean-build evidence did not pass"
            }
            check(evidence["checkout.clean"] == "true") {
                "$label build was not produced from a clean checkout"
            }
            val recordedRoot =
                evidence["checkout.root"]
                    ?.let(::File)
                    ?.takeIf(File::isAbsolute)
                    ?.canonicalFile
            check(recordedRoot?.isDirectory == true) {
                "$label checkout root is unavailable"
            }
            val actualRoot =
                File(
                    commandOutput(
                        recordedRoot,
                        "git",
                        "rev-parse",
                        "--show-toplevel"
                    ).trim()
                ).canonicalFile
            check(actualRoot == recordedRoot) {
                "$label checkout root is not its Git top level"
            }
            val recordedGitDirectory =
                evidence["checkout.gitDirectory"]
                    ?.let(::File)
                    ?.takeIf(File::isAbsolute)
                    ?.canonicalFile
            val actualGitDirectory =
                File(
                    commandOutput(
                        recordedRoot,
                        "git",
                        "rev-parse",
                        "--absolute-git-dir"
                    ).trim()
                ).canonicalFile
            check(
                recordedGitDirectory?.isDirectory == true
                        && recordedGitDirectory ==
                        actualGitDirectory
            ) {
                "$label Git directory is unavailable"
            }
            val fingerprint =
                gitWorkspaceFingerprint(recordedRoot)
            check(!fingerprint.dirty) {
                "$label checkout is no longer clean"
            }
            check(
                evidence["checkout.workspaceSha256"] ==
                    fingerprint.workspaceSha256
            ) {
                "$label checkout source fingerprint changed"
            }
            check(
                evidence["checkout.pathCount"] ==
                    fingerprint.pathCount.toString()
            ) {
                "$label checkout source path count changed"
            }
            check(
                evidence["checkout.gitStatusSha256"] ==
                    fingerprint.statusSha256
            ) {
                "$label checkout Git status fingerprint changed"
            }
            check(evidence["commit"] == currentCommit) {
                "$label build commit ${evidence["commit"]} " +
                    "does not match $currentCommit"
            }
            check(
                evidence["project.version"] ==
                    project.version.toString()
            ) {
                "$label build used a different project version"
            }
            check(
                evidence["dependency.mode"] ==
                    blueLanguageDependencyMode
            ) {
                "$label build used a different dependency mode"
            }
            check(
                evidence["dependency.coordinate"] ==
                    blueLanguageDeclaredCoordinate
            ) {
                "$label build used a different declared dependency"
            }
            check(
                evidence["dependency.effectiveCoordinate"]
                    ?.isNotEmpty() == true
            ) {
                "$label build has no effective dependency coordinate"
            }
            val buildArtifacts =
                linkedMapOf<String, Map<String, String>>()
            for (artifactName in artifactNames) {
                val pathKey =
                    "artifact.$artifactName.path"
                val bytesKey =
                    "artifact.$artifactName.bytes"
                val hashKey =
                    "artifact.$artifactName.sha256"
                val relativePath =
                    evidence[pathKey]
                        ?: throw GradleException(
                            "$label $artifactName path is unavailable"
                        )
                check(
                    relativePath ==
                        expectedArtifactPaths.getValue(
                            artifactName
                        )
                ) {
                    "$label $artifactName path is not the expected " +
                        "release output: $relativePath"
                }
                check(!File(relativePath).isAbsolute) {
                    "$label $artifactName path must be relative"
                }
                val artifact =
                    File(recordedRoot, relativePath)
                        .canonicalFile
                check(
                    artifact.toPath().startsWith(
                        recordedRoot.toPath()
                    ) &&
                        artifact.isFile
                ) {
                    "$label $artifactName artifact is unavailable " +
                        "under its authenticated checkout"
                }
                val recordedBytes =
                    evidence[bytesKey]?.toLongOrNull()
                check(
                    recordedBytes != null &&
                        recordedBytes == artifact.length()
                ) {
                    "$label $artifactName byte length differs from " +
                        "its receipt"
                }
                val recordedHash = evidence[hashKey]
                val actualHash = sha256(artifact)
                check(
                    recordedHash?.matches(
                        Regex("[0-9a-f]{64}")
                    ) == true &&
                        recordedHash == actualHash
                ) {
                    "$label $artifactName artifact hash differs from " +
                        "its receipt"
                }
                buildArtifacts[artifactName] =
                    mapOf(
                        "path" to relativePath,
                        "bytes" to recordedBytes.toString(),
                        "sha256" to actualHash
                    )
            }
            val dependencyPath =
                evidence["dependency.artifact.path"]
                    ?: throw GradleException(
                        "$label Blue Language artifact path is unavailable"
                    )
            check(
                dependencyPath ==
                    expectedDependencyArtifactPath &&
                    !File(dependencyPath).isAbsolute
            ) {
                "$label Blue Language artifact path is not the " +
                    "expected receipt-owned copy"
            }
            val dependencyArtifact =
                File(recordedRoot, dependencyPath)
                    .canonicalFile
            check(
                dependencyArtifact.toPath().startsWith(
                    recordedRoot.toPath()
                ) &&
                    dependencyArtifact.isFile
            ) {
                "$label Blue Language artifact copy is unavailable " +
                    "under its authenticated checkout"
            }
            val dependencyBytes =
                evidence["dependency.artifact.bytes"]
                    ?.toLongOrNull()
            check(
                dependencyBytes != null &&
                    dependencyBytes ==
                    dependencyArtifact.length()
            ) {
                "$label Blue Language artifact byte length differs " +
                    "from its receipt"
            }
            val dependencyHash =
                evidence["dependency.artifact.sha256"]
            val actualDependencyHash =
                sha256(dependencyArtifact)
            check(
                dependencyHash?.matches(
                    Regex("[0-9a-f]{64}")
                ) == true &&
                    dependencyHash ==
                    actualDependencyHash
            ) {
                "$label Blue Language artifact hash differs from " +
                    "its receipt"
            }
            check(
                evidence["dependency.aggregateCompatibilityOnly"] ==
                    "true"
            ) {
                "$label aggregate artifact is not labelled smoke-only"
            }
            check(
                evidence["dependency.focused.declaredCoordinates"] ==
                    blueLanguageFocusedCoordinates.joinToString(",")
            ) {
                "$label focused dependency coordinates differ"
            }
            val focusedGraphHash =
                evidence["dependency.focused.graphSha256"]
            check(
                focusedGraphHash?.matches(Regex("[0-9a-f]{64}")) ==
                    true
            ) {
                "$label focused dependency graph hash is unavailable"
            }
            val focusedArtifactCount =
                evidence["dependency.focused.artifactCount"]
                    ?.toIntOrNull()
            check(
                focusedArtifactCount != null &&
                    focusedArtifactCount > 0
            ) {
                "$label focused dependency artifact count is invalid"
            }
            val focusedArtifacts =
                (0 until focusedArtifactCount).map { index ->
                    val prefix =
                        "dependency.focused.artifact.%03d".format(index)
                    val coordinate = evidence["$prefix.coordinate"]
                    val component = evidence["$prefix.component"]
                    val projectPath =
                        evidence["$prefix.projectPath"].orEmpty()
                    val origin = evidence["$prefix.origin"]
                    val relativePath = evidence["$prefix.path"]
                    check(
                        coordinate?.split(':')?.size == 3 &&
                            component?.isNotEmpty() == true &&
                            origin in setOf(
                                "included-build-project",
                                "external-module"
                            ) &&
                            relativePath?.startsWith(
                                "build/reports/bex-release/" +
                                    "clean-build-inputs/focused-language/"
                            ) == true &&
                            !File(relativePath).isAbsolute
                    ) {
                        "$label focused artifact $index metadata is invalid"
                    }
                    val copiedArtifact =
                        File(recordedRoot, relativePath).canonicalFile
                    check(
                        copiedArtifact.toPath().startsWith(
                            recordedRoot.toPath()
                        ) && copiedArtifact.isFile
                    ) {
                        "$label focused artifact $coordinate is unavailable"
                    }
                    val recordedBytes =
                        evidence["$prefix.bytes"]?.toLongOrNull()
                    val recordedHash = evidence["$prefix.sha256"]
                    check(
                        recordedBytes == copiedArtifact.length() &&
                            recordedHash?.matches(
                                Regex("[0-9a-f]{64}")
                            ) == true &&
                            recordedHash == sha256(copiedArtifact)
                    ) {
                        "$label focused artifact $coordinate differs " +
                            "from its receipt"
                    }
                    val authenticatedCoordinate =
                        requireNotNull(coordinate)
                    val authenticatedComponent =
                        requireNotNull(component)
                    val authenticatedOrigin = requireNotNull(origin)
                    val authenticatedHash = requireNotNull(recordedHash)
                    linkedMapOf<String, String>(
                        "coordinate" to authenticatedCoordinate,
                        "component" to authenticatedComponent,
                        "projectPath" to projectPath,
                        "origin" to authenticatedOrigin,
                        "bytes" to recordedBytes.toString(),
                        "sha256" to authenticatedHash
                    )
                }
            for ((moduleName, expectedPath) in
                blueLanguageFocusedProjectPaths) {
                val matches = focusedArtifacts.filter {
                    it.getValue("coordinate")
                        .substringAfter(':')
                        .substringBefore(':') == moduleName
                }
                check(matches.size == 1) {
                    "$label receipt does not contain exactly one " +
                        "$moduleName artifact"
                }
                if (blueLanguageDependencyMode == "local-composite") {
                    check(
                        matches.single().getValue("origin") ==
                            "included-build-project" &&
                            matches.single().getValue("projectPath") ==
                            expectedPath
                    ) {
                        "$label $moduleName did not originate from " +
                            "$expectedPath"
                    }
                }
            }
            authenticatedRoots[label] = recordedRoot
            authenticatedGitDirectories[label] =
                actualGitDirectory
            authenticatedFingerprints[label] =
                fingerprint
            authenticatedArtifacts[label] =
                buildArtifacts
            authenticatedDependencyArtifacts[label] =
                mapOf(
                    "path" to dependencyPath,
                    "bytes" to dependencyBytes.toString(),
                    "sha256" to actualDependencyHash
                )
            authenticatedFocusedArtifacts[label] = focusedArtifacts
            authenticatedFocusedGraphHashes[label] = focusedGraphHash
        }
        check(
            authenticatedRoots.getValue("first") !=
                authenticatedRoots.getValue("second")
        ) {
            "Independent builds used the same checkout root"
        }
        check(
            authenticatedGitDirectories.getValue("first") !=
                authenticatedGitDirectories.getValue("second")
        ) {
            "Independent builds used the same Git directory"
        }
        check(
            authenticatedFingerprints.getValue("first")
                .workspaceSha256 ==
                authenticatedFingerprints.getValue("second")
                    .workspaceSha256
        ) {
            "Independent builds used different source bytes"
        }
        check(
            authenticatedFingerprints.getValue("first")
                .pathCount ==
                authenticatedFingerprints.getValue("second")
                    .pathCount
        ) {
            "Independent builds used different source path sets"
        }
        val values =
            linkedMapOf(
                "schema" to
                    "blue-bex-independent-clean-builds/1.2",
                "status" to "passed",
                "commit" to currentCommit,
                "first.checkout.clean" to "true",
                "second.checkout.clean" to "true",
                "first.checkout.root" to
                    authenticatedRoots.getValue("first")
                        .canonicalPath,
                "second.checkout.root" to
                    authenticatedRoots.getValue("second")
                        .canonicalPath,
                "first.checkout.gitDirectory" to
                    authenticatedGitDirectories
                        .getValue("first")
                        .canonicalPath,
                "second.checkout.gitDirectory" to
                    authenticatedGitDirectories
                        .getValue("second")
                        .canonicalPath,
                "first.checkout.gitStatusSha256" to
                    authenticatedFingerprints
                        .getValue("first")
                        .statusSha256,
                "second.checkout.gitStatusSha256" to
                    authenticatedFingerprints
                        .getValue("second")
                        .statusSha256,
                "first.checkout.workspaceSha256" to
                    authenticatedFingerprints
                        .getValue("first")
                        .workspaceSha256,
                "second.checkout.workspaceSha256" to
                    authenticatedFingerprints
                        .getValue("second")
                        .workspaceSha256,
                "first.checkout.pathCount" to
                    authenticatedFingerprints
                        .getValue("first")
                        .pathCount.toString(),
                "second.checkout.pathCount" to
                    authenticatedFingerprints
                        .getValue("second")
                        .pathCount.toString(),
                "first.evidence.path" to
                    firstPath.canonicalPath,
                "second.evidence.path" to
                    secondPath.canonicalPath,
                "first.evidence.sha256" to sha256(firstPath),
                "second.evidence.sha256" to sha256(secondPath),
                "project.version" to project.version.toString(),
                "dependency.mode" to
                    first.getValue("dependency.mode"),
                "dependency.coordinate" to
                    first.getValue("dependency.coordinate"),
                "dependency.effectiveCoordinate" to
                    first.getValue(
                        "dependency.effectiveCoordinate"
                    ),
                "dependency.artifact.sha256" to
                    authenticatedDependencyArtifacts
                        .getValue("first")
                        .getValue("sha256"),
                "dependency.artifact.path" to
                    authenticatedDependencyArtifacts
                        .getValue("first")
                        .getValue("path"),
                "dependency.artifact.bytes" to
                    authenticatedDependencyArtifacts
                        .getValue("first")
                        .getValue("bytes"),
                "composite.path" to
                    first.getValue("composite.path"),
                "composite.commit" to
                    first.getValue("composite.commit"),
                "composite.dirty" to
                    first.getValue("composite.dirty"),
                "composite.gitStatusSha256" to
                    first.getValue(
                        "composite.gitStatusSha256"
                    ),
                "composite.workspaceSha256" to
                    first.getValue(
                        "composite.workspaceSha256"
                    ),
                "composite.pathCount" to
                    first.getValue("composite.pathCount")
            )
        for ((label, artifacts) in authenticatedArtifacts) {
            for ((artifactName, artifact) in artifacts) {
                for ((field, value) in artifact) {
                    values[
                        "$label.artifact.$artifactName.$field"
                    ] = value
                }
            }
        }
        for ((label, artifact) in
            authenticatedDependencyArtifacts) {
            for ((field, value) in artifact) {
                values[
                    "$label.dependency.artifact.$field"
                ] = value
            }
        }
        check(
            first["dependency.mode"] ==
                second["dependency.mode"]
        ) {
            "Clean builds used different dependency modes"
        }
        check(
            first["dependency.coordinate"] ==
                second["dependency.coordinate"]
        ) {
            "Clean builds used different dependency coordinates"
        }
        check(
            first["dependency.effectiveCoordinate"] ==
                second["dependency.effectiveCoordinate"]
        ) {
            "Clean builds resolved different effective dependency coordinates"
        }
        val firstDependencyHash =
            authenticatedDependencyArtifacts
                .getValue("first")
                .getValue("sha256")
        val secondDependencyHash =
            authenticatedDependencyArtifacts
                .getValue("second")
                .getValue("sha256")
        check(
            firstDependencyHash.matches(
                Regex("[0-9a-f]{64}")
            )
        ) {
            "First build has no exact Language artifact hash"
        }
        check(
            firstDependencyHash ==
                secondDependencyHash
        ) {
            "Clean builds resolved different Language artifacts"
        }
        val firstFocusedGraphHash =
            authenticatedFocusedGraphHashes.getValue("first")
        val secondFocusedGraphHash =
            authenticatedFocusedGraphHashes.getValue("second")
        check(firstFocusedGraphHash == secondFocusedGraphHash) {
            "Clean builds resolved different focused Language graphs"
        }
        check(
            authenticatedFocusedArtifacts.getValue("first") ==
                authenticatedFocusedArtifacts.getValue("second")
        ) {
            "Clean builds resolved different focused Language JAR sets"
        }
        val verifierFocusedResolution =
            resolveFocusedLanguageEvidence(
                blueLanguageFocusedResolution,
                blueLanguageFocusedProjectPaths,
                blueLanguageDependencyMode == "local-composite"
            )
        check(
            verifierFocusedResolution.graphSha256 ==
                firstFocusedGraphHash
        ) {
            "Clean builds did not use the verifier's exact focused " +
                "Language component graph"
        }
        values["dependency.focused.declaredCoordinates"] =
            blueLanguageFocusedCoordinates.joinToString(",")
        values["dependency.focused.graphSha256"] =
            firstFocusedGraphHash
        values["dependency.focused.artifactCount"] =
            authenticatedFocusedArtifacts.getValue("first")
                .size.toString()
        authenticatedFocusedArtifacts.getValue("first")
            .forEachIndexed { index, artifact ->
                val prefix =
                    "dependency.focused.artifact.%03d".format(index)
                artifact.forEach { (field, value) ->
                    values["$prefix.$field"] = value
                }
            }
        val verifierLanguageArtifacts =
            blueLanguageAggregateCompatibility
                .resolvedConfiguration
                .resolvedArtifacts
                .filter {
                    it.moduleVersion.id.group == "blue.language" &&
                        it.name == "blue-language-java" &&
                        it.extension == "jar"
                }
        check(verifierLanguageArtifacts.size == 1) {
            "Verifier did not resolve exactly one Blue Language artifact"
        }
        val verifierLanguageArtifact =
            verifierLanguageArtifacts.single()
        val verifierLanguageCoordinate =
            (
                verifierLanguageArtifact.moduleVersion.id.group +
                    ":" +
                    verifierLanguageArtifact.name +
                    ":" +
                    verifierLanguageArtifact.moduleVersion.id.version
            )
        check(
            verifierLanguageCoordinate ==
                first["dependency.effectiveCoordinate"]
        ) {
            "Clean builds did not resolve the verifier's exact Blue " +
                "Language coordinate"
        }
        check(
            sha256(verifierLanguageArtifact.file) ==
                firstDependencyHash
        ) {
            "Clean builds did not use the verifier's exact Blue " +
                "Language artifact"
        }
        val compositeKeys =
            listOf(
                "composite.path",
                "composite.commit",
                "composite.dirty",
                "composite.gitStatusSha256",
                "composite.workspaceSha256",
                "composite.pathCount"
            )
        for (key in compositeKeys) {
            check(first[key] == second[key]) {
                "Clean builds used different $key values"
            }
        }
        if (blueLanguageDependencyMode == "local-composite") {
            check(
                first["composite.commit"]
                    ?.matches(Regex("[0-9a-f]{40}")) ==
                    true
            ) {
                "Local-composite source commit is unavailable"
            }
            check(
                first["composite.workspaceSha256"]
                    ?.matches(Regex("[0-9a-f]{64}")) ==
                true
            ) {
                "Local-composite source fingerprint is unavailable"
            }
            check(
                first["composite.dirty"] == "false"
                        && second["composite.dirty"] ==
                        "false"
            ) {
                "Local-composite clean builds require a clean " +
                    "Blue Language checkout"
            }
            check(
                first["composite.gitStatusSha256"] ==
                    sha256(ByteArray(0))
            ) {
                "Local-composite Git status is not clean"
            }
            val activeCompositePath =
                blueLanguageCompositePath
                    ?.let(::file)
                    ?.canonicalFile
            val recordedCompositePath =
                first["composite.path"]
                    ?.let(::File)
                    ?.canonicalFile
            check(
                activeCompositePath?.isDirectory == true
                        && recordedCompositePath ==
                        activeCompositePath
            ) {
                "Local-composite source path changed"
            }
            val activeComposite =
                gitWorkspaceFingerprint(activeCompositePath)
            check(
                !activeComposite.dirty
                        && activeComposite.commit ==
                        first["composite.commit"]
                        && activeComposite.statusSha256 ==
                        first["composite.gitStatusSha256"]
                        && activeComposite.workspaceSha256 ==
                        first["composite.workspaceSha256"]
                        && activeComposite.pathCount.toString() ==
                        first["composite.pathCount"]
            ) {
                "Local-composite source state changed"
            }
        }
        for (artifactName in artifactNames) {
            val firstHash =
                authenticatedArtifacts
                    .getValue("first")
                    .getValue(artifactName)
                    .getValue("sha256")
            val secondHash =
                authenticatedArtifacts
                    .getValue("second")
                    .getValue(artifactName)
                    .getValue("sha256")
            check(
                firstHash.matches(Regex("[0-9a-f]{64}"))
            ) {
                "First $artifactName hash is unavailable"
            }
            check(firstHash == secondHash) {
                "$artifactName differs across clean builds: " +
                    "$firstHash != $secondHash"
            }
            values["artifact.$artifactName.sha256"] =
                firstHash
            values["artifact.$artifactName.byteIdentical"] =
                "true"
        }
        writeEvidence(
            independentCleanBuildEvidence.asFile,
            values
        )
    }
}

val binaryApiEvidence =
    layout.buildDirectory.file(
        "reports/bex-release/binary-api.properties"
    )
val binaryApiManifest =
    layout.buildDirectory.file(
        "reports/bex-release/public-api.txt"
    )
val requiredBinaryApi =
    layout.projectDirectory.file(
        "src/test/resources/hosted-release/" +
            "required-public-api.txt"
    )
val generateBinaryApiManifest by tasks.registering(JavaExec::class) {
    group = "verification"
    description =
        "Generates a deterministic descriptor-level public/protected API manifest from the packaged JAR."
    dependsOn(tasks.testClasses, mainJar)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set(
        "blue.bex.conformance.BexBinaryApiManifestMain"
    )
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    )
    doFirst {
        setArgs(
            listOf(
                mainJar.get().archiveFile.get().asFile.absolutePath,
                binaryApiManifest.get().asFile.absolutePath
            )
        )
    }
    inputs.file(mainJar.flatMap { it.archiveFile })
    outputs.file(binaryApiManifest)
    outputs.upToDateWhen { false }
}
val binaryApiCheck by tasks.registering(Test::class) {
    group = "verification"
    description =
        "Runs the BEX 2.0 API-surface checks against the packaged binary JAR."
    dependsOn(
        tasks.testClasses,
        mainJar,
        generateBinaryApiManifest
    )
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath =
        sourceSets.test.get().output +
            files(mainJar.flatMap { it.archiveFile }) +
            configurations.testRuntimeClasspath.get()
    useJUnitPlatform()
    include("**/Bex20ApiSurfaceTest.class")
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    )
    reports.junitXml.required.set(true)
    reports.html.required.set(true)
    inputs.file(requiredBinaryApi)
    outputs.file(binaryApiEvidence)
    outputs.upToDateWhen { false }
    doFirst {
        binaryApiEvidence.get().asFile.delete()
    }
    doLast {
        val artifact = mainJar.get().archiveFile.get().asFile
        val manifest = binaryApiManifest.get().asFile
        val required = requiredBinaryApi.asFile
        check(manifest.isFile) {
            "Binary API manifest is missing: $manifest"
        }
        check(required.isFile) {
            "Required binary API signature set is missing: $required"
        }
        val actualSignatures = manifest.readLines()
            .map { it.trimEnd() }
        val requiredSignatures = required.readLines()
            .map { it.trimEnd() }
        check(actualSignatures == requiredSignatures) {
            val firstDifference =
                (0 until maxOf(
                    actualSignatures.size,
                    requiredSignatures.size
                )).firstOrNull { index ->
                    actualSignatures.getOrNull(index) !=
                        requiredSignatures.getOrNull(index)
                }
            "Packaged JAR public/protected API differs from the exact " +
                "first-public BEX 2.0 baseline at line " +
                "${firstDifference?.plus(1) ?: 1}:\n" +
                "expected=" +
                requiredSignatures.getOrNull(firstDifference ?: 0) +
                "\nactual=" +
                actualSignatures.getOrNull(firstDifference ?: 0)
        }
        writeEvidence(
            binaryApiEvidence.get().asFile,
            mapOf(
                "artifact.path" to
                    artifact.relativeTo(projectDir).invariantSeparatorsPath,
                "artifact.sha256" to sha256(artifact),
                "manifest.path" to
                    manifest.relativeTo(projectDir).invariantSeparatorsPath,
                "manifest.sha256" to sha256(manifest),
                "manifest.schema" to
                    "blue-bex-binary-api-manifest/1.0",
                "required.path" to
                    required.relativeTo(projectDir).invariantSeparatorsPath,
                "required.sha256" to sha256(required),
                "required.signatureCount" to
                    requiredSignatures.size.toString(),
                "required.missingCount" to "0",
                "required.unexpectedCount" to "0",
                "required.comparison" to "exact-match",
                "status" to "passed",
                "testClass" to "blue.bex.api.Bex20ApiSurfaceTest"
            )
        )
    }
}

val java8BytecodeEvidence =
    layout.buildDirectory.file(
        "reports/bex-release/java8-bytecode.properties"
    )
val java8BytecodeCheck by tasks.registering {
    group = "verification"
    description =
        "Verifies that every class in the packaged main JAR is Java 8 bytecode."
    dependsOn(mainJar)
    inputs.file(mainJar.flatMap { it.archiveFile })
    outputs.file(java8BytecodeEvidence)
    outputs.upToDateWhen { false }
    doFirst {
        java8BytecodeEvidence.get().asFile.delete()
    }
    doLast {
        val artifact = mainJar.get().archiveFile.get().asFile
        val expectedMagic = "cafebabe"
        val expectedMajor = 52
        val observedMajors = sortedSetOf<Int>()
        val classCount =
            ZipFile(artifact).use { archive ->
                val classEntries =
                    archive.entries().asSequence()
                        .filter {
                            !it.isDirectory &&
                                it.name.endsWith(".class")
                        }
                        .sortedBy { it.name }
                        .toList()
                check(classEntries.isNotEmpty()) {
                    "Packaged main JAR contains no class entries: $artifact"
                }
                classEntries.forEach { entry ->
                    val header = ByteArray(8)
                    val bytesRead =
                        archive.getInputStream(entry).buffered().use { input ->
                            var offset = 0
                            while (offset < header.size) {
                                val read =
                                    input.read(
                                        header,
                                        offset,
                                        header.size - offset
                                    )
                                if (read < 0) {
                                    break
                                }
                                offset += read
                            }
                            offset
                        }
                    check(bytesRead == header.size) {
                        "Truncated class header in ${entry.name}: " +
                            "$bytesRead bytes"
                    }
                    val magic =
                        header.take(4).joinToString("") {
                            "%02x".format(it.toInt() and 0xff)
                        }
                    check(magic == expectedMagic) {
                        "Invalid class magic in ${entry.name}: $magic"
                    }
                    val major =
                        ((header[6].toInt() and 0xff) shl 8) or
                            (header[7].toInt() and 0xff)
                    observedMajors.add(major)
                    check(major == expectedMajor) {
                        "Non-Java-8 bytecode in ${entry.name}: " +
                            "major $major (expected $expectedMajor)"
                    }
                }
                classEntries.size
            }
        writeEvidence(
            java8BytecodeEvidence.get().asFile,
            mapOf(
                "artifact.path" to
                    artifact.relativeTo(projectDir).invariantSeparatorsPath,
                "artifact.sha256" to sha256(artifact),
                "classCount" to classCount.toString(),
                "expected.magic" to expectedMagic.uppercase(),
                "expected.major" to expectedMajor.toString(),
                "observed.magic" to expectedMagic.uppercase(),
                "observed.major" to observedMajors.joinToString(","),
                "schema" to
                    "blue-bex-java8-bytecode-evidence/1.0",
                "status" to "passed"
            )
        )
    }
}

val benchmarkCompilationEvidence =
    layout.buildDirectory.file(
        "reports/bex-release/benchmark-compilation.properties"
    )
val benchmarkCompilationCheck by tasks.registering {
    group = "verification"
    description =
        "Verifies that the compile-only local benchmark builds under Java 8; it does not run timing."
    dependsOn(tasks.testClasses)
    val benchmarkSource =
        layout.projectDirectory.file(
            "src/test/java/blue/bex/BexLocalBenchmarkTest.java"
        )
    val benchmarkClass =
        layout.buildDirectory.file(
            "classes/java/test/blue/bex/BexLocalBenchmarkTest.class"
        )
    inputs.file(benchmarkSource)
    inputs.file(benchmarkClass)
    outputs.file(benchmarkCompilationEvidence)
    outputs.upToDateWhen { false }
    doFirst {
        benchmarkCompilationEvidence.get().asFile.delete()
    }
    doLast {
        val source = benchmarkSource.asFile
        val compiled = benchmarkClass.get().asFile
        check(source.isFile) {
            "Benchmark source is missing: $source"
        }
        check(compiled.isFile) {
            "Benchmark did not compile to: $compiled"
        }
        writeEvidence(
            benchmarkCompilationEvidence.get().asFile,
            mapOf(
                "class.path" to
                    compiled.relativeTo(projectDir).invariantSeparatorsPath,
                "class.sha256" to sha256(compiled),
                "source.path" to
                    source.relativeTo(projectDir).invariantSeparatorsPath,
                "source.sha256" to sha256(source),
                "status" to "passed",
                "timingExecuted" to "false"
            )
        )
    }
}

val dependencyResolutionEvidence =
    layout.buildDirectory.file(
        "reports/bex-release/dependency-resolution.properties"
    )
val writeDependencyResolutionEvidence by tasks.registering {
    group = "verification"
    description =
        "Verifies focused Language resolution and the smoke-only aggregate " +
            "facade against strict standalone provenance evidence."
    dependsOn(
        verifyBlueLanguageAggregateCompatibility,
        writeFocusedLanguageResolutionEvidence
    )
    outputs.file(dependencyResolutionEvidence)
    outputs.upToDateWhen { false }
    doFirst {
        dependencyResolutionEvidence.get().asFile.delete()
    }
    doLast {
        val matches =
            blueLanguageAggregateCompatibility
                .resolvedConfiguration
                .resolvedArtifacts
                .filter {
                    it.moduleVersion.id.group == "blue.language" &&
                        it.name == "blue-language-java" &&
                        it.extension == "jar"
                }
        check(matches.size == 1) {
            "Expected exactly one smoke-only blue-language-java artifact, found " +
                matches.joinToString { it.file.absolutePath }
        }
        val artifact = matches.single()
        val component = artifact.id.componentIdentifier
        val compositeDirectory =
            blueLanguageCompositePath
                ?.let { file(it).canonicalFile }
        val artifactHash = sha256(artifact.file)
        val focusedResolution =
            resolveFocusedLanguageEvidence(
                blueLanguageFocusedResolution,
                blueLanguageFocusedProjectPaths,
                blueLanguageDependencyMode == "local-composite"
            )
        val provenanceStatus: String
        val moduleVersionCacheAcceptance: String
        if (blueLanguageDependencyMode == "standalone-published") {
            check(
                publishedBlueLanguageCoordinate ==
                    blueLanguageDeclaredCoordinate
            ) {
                "Recorded Maven Central coordinate differs from the " +
                    "declared dependency: $publishedBlueLanguageCoordinate"
            }
            check(
                publishedBlueLanguageRepository ==
                    "https://repo1.maven.org/maven2"
            ) {
                "Unrecognized recorded Maven Central provenance: " +
                    publishedBlueLanguageRepository
            }
            check(artifactHash == publishedBlueLanguageSha256) {
                "Resolved standalone artifact does not match the recorded " +
                    "Maven Central SHA-256: $artifactHash != " +
                    publishedBlueLanguageSha256
            }
            provenanceStatus =
                "verified-against-recorded-maven-central-hash"
            // This is deliberately narrower than claiming that the entire
            // Gradle cache was clean. Project configuration captures whether
            // this exact Blue Language module/version directory was absent;
            // resolution above then verifies the resulting JAR against the
            // source-controlled Maven Central hash.
            val allRequiredCachesInitiallyAbsent =
                blueLanguageModuleVersionCacheInitiallyAbsent &&
                    blueLanguageFocusedModuleVersionCachesInitiallyAbsent
                        .values.all { it }
            moduleVersionCacheAcceptance =
                if (!blueLanguageRequireFreshModuleCache.get()) {
                    "not-required-for-current-run"
                } else if (allRequiredCachesInitiallyAbsent) {
                    "passed"
                } else {
                    "failed"
                }
        } else {
            provenanceStatus = "not-applicable-local-composite"
            moduleVersionCacheAcceptance = "not-executed"
        }
        val values =
            linkedMapOf(
                "schema" to
                    "blue-bex-dependency-resolution-evidence/1.0",
                "status" to "resolved",
                "mode" to blueLanguageDependencyMode,
                "declared.coordinate" to
                    blueLanguageDeclaredCoordinate,
                "effective.component" to component.displayName,
                "effective.group" to artifact.moduleVersion.id.group,
                "effective.name" to artifact.name,
                "effective.version" to
                    artifact.moduleVersion.id.version,
                "artifact.path" to artifact.file.canonicalPath,
                "artifact.bytes" to artifact.file.length().toString(),
                "artifact.sha256" to artifactHash,
                "aggregate.compatibilityOnly" to "true",
                "focused.declaredCoordinates" to
                    blueLanguageFocusedCoordinates.joinToString(","),
                "focused.graphSha256" to
                    focusedResolution.graphSha256,
                "focused.componentCount" to
                    focusedResolution.components.size.toString(),
                "focused.edgeCount" to
                    focusedResolution.edges.size.toString(),
                "focused.artifactCount" to
                    focusedResolution.artifacts.size.toString(),
                "composite.path" to
                    (compositeDirectory?.path ?: ""),
                "repository.policy" to "maven-central-only",
                "provenance.status" to provenanceStatus,
                "provenance.recorded.repository" to
                    publishedBlueLanguageRepository,
                "provenance.recorded.coordinate" to
                    publishedBlueLanguageCoordinate,
                "provenance.recorded.sha256" to
                    publishedBlueLanguageSha256,
                "provenance.networkFetchObservation" to
                    "not-exposed-by-gradle-resolution-api",
                "cache.blueLanguageModuleVersionPath" to
                    blueLanguageModuleVersionCache.canonicalPath,
                "cache.blueLanguageModuleVersionInitiallyAbsent" to
                    blueLanguageModuleVersionCacheInitiallyAbsent.toString(),
                "cache.freshProofRequired" to
                    blueLanguageRequireFreshModuleCache.get().toString(),
                "cache.acceptance" to moduleVersionCacheAcceptance,
                "cache.acceptanceScope" to
                    "standalone-published-focused-and-aggregate-language-module-version-caches"
            )
        blueLanguageFocusedModuleVersionCaches
            .toSortedMap()
            .forEach { (moduleName, cache) ->
                values["cache.focused.$moduleName.path"] =
                    cache.canonicalPath
                values["cache.focused.$moduleName.initiallyAbsent"] =
                    blueLanguageFocusedModuleVersionCachesInitiallyAbsent
                        .getValue(moduleName)
                        .toString()
            }
        focusedResolution.artifacts.forEachIndexed { index, focused ->
            val prefix = "focused.artifact.%03d".format(index)
            values["$prefix.coordinate"] = focused.coordinate
            values["$prefix.component"] = focused.component
            values["$prefix.projectPath"] =
                focused.projectPath.orEmpty()
            values["$prefix.bytes"] = focused.bytes.toString()
            values["$prefix.sha256"] = focused.sha256
        }
        writeEvidence(
            dependencyResolutionEvidence.get().asFile,
            values
        )
    }
}

val writeBexConformanceReport by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Writes truthful BEX 2.0 test, coverage, identity, and artifact evidence."
    dependsOn(
        tasks.testClasses,
        mainJar,
        sourcesJarTask,
        verifyDeterministicArchives,
        binaryApiCheck,
        java8BytecodeCheck,
        benchmarkCompilationCheck,
        writeDependencyResolutionEvidence
    )
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("blue.bex.conformance.BexConformanceReportMain")
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    )
    doFirst {
        args(
            project.layout.projectDirectory.asFile.absolutePath,
            project.layout.buildDirectory.get().asFile.absolutePath,
            gradle.gradleVersion,
            project.version.toString(),
            blueLanguageDependencyMode,
            blueLanguageDeclaredCoordinate,
            project.layout.projectDirectory
                .dir(".gradle/bex-hosted-release")
                .asFile
                .absolutePath,
            blueLanguageCompositePath
                ?.let { file(it).canonicalPath }
                .orEmpty()
        )
    }
    outputs.file(
        layout.buildDirectory.file(
            "reports/bex-conformance/report.json"
        )
    )
    outputs.file(
        layout.buildDirectory.file(
            "reports/bex-conformance/report.md"
        )
    )
    outputs.file(
        layout.buildDirectory.file(
            "reports/bex-conformance/release-readiness.properties"
        )
    )
    outputs.upToDateWhen { false }
}
writeBexConformanceReport {
    mustRunAfter(tasks.test)
}

tasks.register("bexConformanceReport") {
    group = "verification"
    description = "Runs all tests and produces the machine-readable BEX 2.0 conformance report."
    dependsOn(tasks.test, writeBexConformanceReport)
}

val bexReleaseEvidence by tasks.registering {
    group = "verification"
    description =
        "Runs tests, conformance, same-tree and independent-clean archive gates, binary API, Java 8 bytecode, benchmark compilation, and writes release evidence."
    dependsOn(tasks.test, writeBexConformanceReport)
    doLast {
        val readiness =
            layout.buildDirectory.file(
                "reports/bex-conformance/release-readiness.properties"
            ).get().asFile
        check(readiness.isFile) {
            "Hosted release readiness evidence was not generated"
        }
        val values =
            readiness.readLines()
                .filter { it.contains("=") }
                .associate {
                    val separator = it.indexOf('=')
                    it.substring(0, separator) to
                        it.substring(separator + 1)
                }
        check(values["releaseReady"] == "true") {
            "Hosted release evidence is incomplete: " +
                (values["reason"]
                    ?: "see build/reports/bex-conformance/report.md")
        }
    }
}

val bexWorkingVerificationReport =
    layout.buildDirectory.file(
        "reports/latest-language-migration/final.json"
    )
val publicApiClassificationLedger =
    layout.projectDirectory.file("docs/public-api-classification.json")
val writeProvisionalBexWorkingReceipt = {
    blueLanguageFocusedResolutionEvidence.get().asFile.delete()
    blueLanguageAggregateCompatibilityEvidence.get().asFile.delete()
    latestLanguageMigrationBaselineEvidence.get().asFile.delete()
    val outputFile = bexWorkingVerificationReport.get().asFile
    outputFile.parentFile.mkdirs()
    outputFile.writeText(
        JsonOutput.prettyPrint(
            JsonOutput.toJson(
                linkedMapOf(
                    "schema" to
                        "blue-bex-working-verification/2.0",
                    "status" to "in-progress-or-failed",
                    "workingReady" to false,
                    "workingFailures" to
                        listOf("verification-did-not-complete"),
                    "recommendedCommand" to
                        "./gradlew bexWorkingVerification " +
                            "-PblueLanguageCompositePath=" +
                            (blueLanguageCompositePath ?: "<required>"),
                    "recommendedCommandExecuted" to false
                )
            )
        ) + "\n"
    )
}
gradle.taskGraph.whenReady {
    val workingReportRequested =
        allTasks.any {
            it.path == ":bexWorkingVerification" ||
                it.path == ":writeBexWorkingVerificationReport"
        }
    if (workingReportRequested && !gradle.startParameter.isDryRun) {
        writeProvisionalBexWorkingReceipt()
    }
}
val initializeBexWorkingVerificationReceipt by tasks.registering {
    group = "verification"
    description =
        "Invalidates migration evidence and writes a provisional red " +
            "receipt before compilation or dependency resolution starts."
    outputs.upToDateWhen { false }
    doLast {
        writeProvisionalBexWorkingReceipt()
    }
}
val writeBexWorkingVerificationReport by tasks.registering {
    group = "verification"
    description =
        "Writes the publication-independent BEX working-verification " +
            "report for the exact local modular Language checkout."
    dependsOn(
        initializeBexWorkingVerificationReceipt,
        tasks.test,
        writeBexConformanceReport,
        sourceReleaseArchive,
        verifyBlueLanguageAggregateCompatibility,
        writeFocusedLanguageResolutionEvidence,
        writeLatestLanguageMigrationBaseline
    )
    val conformanceReport =
        layout.buildDirectory.file(
            "reports/bex-conformance/report.json"
        )
    inputs.file(conformanceReport)
    inputs.file(blueLanguageAggregateCompatibilityEvidence)
    inputs.file(blueLanguageFocusedResolutionEvidence)
    inputs.file(latestLanguageMigrationBaselineEvidence)
    inputs.file(latestLanguageMigrationLock)
    inputs.file(publicApiClassificationLedger)
    inputs.property("dependency.mode", blueLanguageDependencyMode)
    inputs.property(
        "focused.coordinates",
        blueLanguageFocusedCoordinates.joinToString(",")
    )
    outputs.file(bexWorkingVerificationReport)
    outputs.upToDateWhen { false }
    doLast {
        val failures = mutableListOf<String>()
        fun requireWorking(value: Boolean, failure: String) {
            if (!value) failures.add(failure)
        }
        fun mapValue(value: Any?): Map<*, *> =
            value as? Map<*, *> ?: emptyMap<Any, Any>()
        fun child(parent: Map<*, *>, name: String): Map<*, *> =
            mapValue(parent[name])
        fun intValue(parent: Map<*, *>, name: String): Int =
            (parent[name] as? Number)?.toInt() ?: -1
        fun passed(parent: Map<*, *>, name: String): Boolean =
            child(parent, name)["status"] == "passed"

        val conformanceFile = conformanceReport.get().asFile
        check(conformanceFile.isFile) {
            "Conformance report is missing: $conformanceFile"
        }
        @Suppress("UNCHECKED_CAST")
        val report =
            JsonSlurper().parse(conformanceFile)
                as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val focusedResolution =
            JsonSlurper().parse(
                blueLanguageFocusedResolutionEvidence.get().asFile
            ) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val migrationBaseline =
            JsonSlurper().parse(
                latestLanguageMigrationBaselineEvidence.get().asFile
            ) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val publicApiClassification =
            JsonSlurper().parse(publicApiClassificationLedger.asFile)
                as Map<String, Any?>
        val baselineLanguage = child(migrationBaseline, "language")
        val baselineBex = child(migrationBaseline, "bex")
        val languageCzToml = child(baselineLanguage, "czToml")
        val bexCzToml = child(baselineBex, "czToml")
        requireWorking(
            migrationBaseline["status"] == "passed",
            "migration-baseline-lock-validation-not-passing"
        )
        requireWorking(
            baselineLanguage["codeEquivalent"] == true,
            "language-not-code-equivalent-to-verified-implementation"
        )
        requireWorking(
            languageCzToml["matches"] == true,
            "language-cz-toml-differs-from-lock"
        )
        requireWorking(
            bexCzToml["matches"] == true,
            "bex-cz-toml-differs-from-lock"
        )
        val apiInventory =
            child(publicApiClassification, "inventory")
        val apiClassifications =
            child(publicApiClassification, "classifications")
        val classifiedApiTypes =
            apiClassifications.values.flatMap { value ->
                (value as? List<*>)?.map(Any?::toString)
                    ?: emptyList()
            }
        val requiredApiInventory =
            file(apiInventory["path"].toString())
        val requiredApiTypes =
            if (requiredApiInventory.isFile) {
                requiredApiInventory.readLines().mapNotNull { line ->
                    if (line.startsWith("class public ")) {
                        line.substringBefore(" extends ")
                            .substringBefore(" implements ")
                            .removePrefix("class public ")
                            .trim()
                            .substringAfterLast(' ')
                    } else {
                        null
                    }
                }.toSet()
            } else {
                emptySet()
            }
        requireWorking(
            publicApiClassification["schema"] ==
                "blue-bex-public-api-classification/1.0" &&
                requiredApiInventory.isFile &&
                sha256(requiredApiInventory) ==
                apiInventory["sha256"] &&
                classifiedApiTypes.size ==
                (apiInventory["publicTypeCount"] as? Number)
                    ?.toInt() &&
                classifiedApiTypes.toSet().size ==
                classifiedApiTypes.size &&
                classifiedApiTypes.toSet() == requiredApiTypes,
            "public-api-classification-ledger-not-current"
        )
        val productionClasspaths =
            child(focusedResolution, "productionClasspaths")
        requireWorking(
            focusedResolution["status"] == "passed" &&
                focusedResolution["mode"] == "local-composite" &&
                focusedResolution["graphSha256"]
                    ?.toString()
                    ?.matches(Regex("[0-9a-f]{64}")) == true &&
                child(productionClasspaths, "compile")
                    ["aggregatePresent"] == false &&
                child(productionClasspaths, "runtime")
                    ["aggregatePresent"] == false,
            "focused-language-resolution-or-production-classpath-gate-not-passing"
        )
        val totals = child(report, "finalTotals")
        val tests = child(totals, "tests")
        val behavior = child(totals, "behaviorFixtures")
        val gas = child(totals, "gasMicrofixtures")
        val vectors = child(totals, "normativeVectors")
        val operators = child(totals, "operators")

        val testsExecuted = intValue(tests, "executed")
        val testsPassed = intValue(tests, "passed")
        val testsFailed = intValue(tests, "failed")
        val testsSkipped = intValue(tests, "skipped")
        val testsUnclassified =
            if (
                testsExecuted >= 0 && testsPassed >= 0 &&
                testsFailed >= 0 && testsSkipped >= 0
            ) {
                testsExecuted - testsPassed -
                    testsFailed - testsSkipped
            } else {
                -1
            }
        requireWorking(
            testsExecuted > 0 && testsFailed == 0 &&
                testsSkipped == 0 && testsUnclassified == 0 &&
                tests["zeroFailures"] == true &&
                tests["zeroSkips"] == true,
            "ordinary-tests-not-passing-with-zero-skips-and-zero-unclassified"
        )
        requireWorking(
            intValue(behavior, "required") == 105 &&
                intValue(behavior, "executedAndPassing") == 105,
            "behavior-fixtures-not-105-of-105"
        )
        requireWorking(
            intValue(gas, "required") == 30 &&
                intValue(gas, "executedAndPassing") == 30,
            "gas-microfixtures-not-30-of-30"
        )
        requireWorking(
            intValue(vectors, "required") == 60 &&
                intValue(vectors, "executedAndPassing") == 60 &&
                vectors["allPassing"] == true,
            "normative-vectors-not-60-of-60"
        )
        requireWorking(
            intValue(operators, "required") == 86 &&
                intValue(operators, "executedAndPassing") == 86,
            "operator-coverage-not-86-of-86"
        )

        val identities = child(report, "identities")
        val exactIdentitiesPassed =
            identities["bexRegistry"] == requiredBexRegistryIdentity &&
                identities["fixtureBindsRegistry"] ==
                requiredBexRegistryIdentity &&
                identities["gasManifest"] ==
                requiredBexGasManifestIdentity &&
                identities["fixtureBindsGas"] ==
                requiredBexGasManifestIdentity &&
                identities["fixturePackage"] ==
                requiredBexFixturePackageIdentity
        requireWorking(
            exactIdentitiesPassed,
            "normative-registry-gas-or-fixture-identity-mismatch"
        )

        val releaseGates = child(report, "releaseGates")
        requireWorking(
            passed(releaseGates, "deterministicArchives"),
            "bex-owned-reproducibility-check-not-passing"
        )
        requireWorking(
            passed(releaseGates, "binaryApi"),
            "binary-source-api-report-not-passing"
        )
        requireWorking(
            passed(releaseGates, "java8Bytecode"),
            "java8-bytecode-check-not-passing"
        )
        requireWorking(
            child(report, "semanticBoundaryInvocationEvidence")
                ["status"] == "passed" &&
                child(report, "ledgerLifecycleEvidence")
                    ["status"] == "passed",
            "hosted-contracts-boundary-evidence-not-passing"
        )
        val semanticParityPassed =
            child(report, "representationMatrixResult")["status"] ==
                "passed" &&
                child(report, "intrinsicEvidence")["status"] ==
                "passed" &&
                intValue(behavior, "required") == 105 &&
                intValue(behavior, "executedAndPassing") == 105 &&
                vectors["allPassing"] == true &&
                intValue(operators, "executedAndPassing") == 86 &&
                exactIdentitiesPassed
        requireWorking(
            semanticParityPassed,
            "semantic-parity-evidence-not-passing"
        )
        val counterCoverage = child(report, "counterCoverage")
        val gasParityPassed =
            counterCoverage["allMicrofixturesPassing"] == true &&
                counterCoverage["vocabularyComplete"] == true &&
                intValue(counterCoverage, "declaredCounterCount") == 30 &&
                intValue(counterCoverage, "executedMicrofixtureCount") == 30 &&
                intValue(counterCoverage, "passingMicrofixtureCount") == 30 &&
                child(report, "gasExhaustionEvidence")["status"] ==
                "passed" &&
                child(report, "finiteLoopEvidence")["status"] ==
                "passed" &&
                intValue(gas, "executedAndPassing") == 30 &&
                exactIdentitiesPassed
        requireWorking(
            gasParityPassed,
            "gas-parity-evidence-not-passing"
        )
        requireWorking(
            child(child(report, "dependency"), "resolution")
                ["status"] == "passed",
            "aggregate-compatibility-resolution-report-not-passing"
        )
        requireWorking(
            (report["artifacts"] as? List<*>)?.size == 4,
            "working-artifacts-not-all-present"
        )

        fun legacyImportCount(sourceRoot: File): Int =
            if (!sourceRoot.isDirectory) {
                0
            } else {
                sourceRoot.walkTopDown()
                    .filter { it.isFile && it.extension == "java" }
                    .sumOf { source ->
                        source.useLines { lines ->
                            lines.count { line ->
                                forbiddenLegacyImportPatterns.any {
                                    pattern -> pattern.containsMatchIn(line)
                                }
                            }
                        }
                    }
            }
        val productionLegacyImports =
            legacyImportCount(file("src/main/java"))
        val testLegacyImports =
            legacyImportCount(file("src/test/java"))
        requireWorking(
            productionLegacyImports == 0,
            "production-legacy-language-imports-present"
        )
        requireWorking(
            testLegacyImports == 0,
            "test-legacy-language-imports-present"
        )

        val compositeDirectory =
            blueLanguageCompositePath
                ?.let { file(it).canonicalFile }
        requireWorking(
            blueLanguageDependencyMode == "local-composite" &&
                compositeDirectory?.isDirectory == true,
            "bex-working-verification-requires-blueLanguageCompositePath"
        )
        val languageFingerprint =
            compositeDirectory
                ?.takeIf { it.isDirectory }
                ?.let(::gitWorkspaceFingerprint)
        requireWorking(
            languageFingerprint != null &&
                !languageFingerprint.dirty,
            "local-language-checkout-is-dirty-or-unavailable"
        )

        val aggregateEvidence = readEvidence(
            blueLanguageAggregateCompatibilityEvidence
                .get().asFile
        )
        requireWorking(
            aggregateEvidence["status"] == "passed" &&
                aggregateEvidence["mode"] == "local-composite",
            "aggregate-language-compatibility-smoke-not-passing"
        )

        val workingReady = failures.isEmpty()
        val output = LinkedHashMap<String, Any?>(report)
        output["schema"] =
            "blue-bex-working-verification/2.0"
        output["status"] =
            if (workingReady) "passed" else "failed"
        output["workingReady"] = workingReady
        output["workingFailures"] = failures
        val workingTests = linkedMapOf<String, Any?>()
        tests.forEach { (key, value) ->
            workingTests[key.toString()] = value
        }
        workingTests["unclassified"] = testsUnclassified
        workingTests["zeroUnclassified"] = testsUnclassified == 0
        val workingFinalTotals = linkedMapOf<String, Any?>()
        totals.forEach { (key, value) ->
            workingFinalTotals[key.toString()] = value
        }
        workingFinalTotals["tests"] = workingTests
        output["tests"] = workingTests
        output["finalTotals"] = workingFinalTotals
        val hostedStandaloneMatrix =
            child(report, "hostedStandaloneMatrix")
        val standalonePublished =
            child(
                hostedStandaloneMatrix,
                "standalonePublished"
            )
        val strictReleaseFailures =
            ((report["currentModeFailures"] as? List<*>)
                ?.map(Any?::toString)
                ?: emptyList()).toMutableList()
        if (hostedStandaloneMatrix["allRequiredModesPassed"] != true) {
            strictReleaseFailures +=
                "published-local-mode-matrix-not-passing"
        }
        output["strictRelease"] =
            linkedMapOf(
                "releaseReady" to report["releaseReady"],
                "failures" to strictReleaseFailures.distinct(),
                "allRequiredModesPassed" to
                    hostedStandaloneMatrix["allRequiredModesPassed"],
                "publishedModeStatus" to
                    (standalonePublished["status"] ?: "not-executed"),
                "evidence" to hostedStandaloneMatrix
            )
        output["publishedModeStatus"] =
            standalonePublished["status"] ?: "not-executed"
        output["recommendedCommand"] =
            "./gradlew bexWorkingVerification " +
                "-PblueLanguageCompositePath=" +
                (compositeDirectory?.path ?: "<required>")
        output["recommendedCommandExecuted"] =
            gradle.startParameter.taskNames.any { requestedTask ->
                requestedTask.substringAfterLast(':') ==
                    "bexWorkingVerification"
            }
        output["reportProducerTask"] =
            ":writeBexWorkingVerificationReport"
        output["migrationBaseline"] = migrationBaseline
        output["languageCodeEquivalence"] = baselineLanguage
        output["sourceApiInventory"] =
            migrationBaseline["sourceApiInventory"]
        output["publicApiClassification"] =
            publicApiClassification
        output["migrationLedger"] =
            migrationBaseline["migrationLedger"]
        output["focusedLanguageResolution"] = focusedResolution
        output["semanticParity"] =
            linkedMapOf(
                "status" to
                    if (semanticParityPassed) "passed" else "failed",
                "normativeVectors" to vectors,
                "behaviorFixtures" to behavior,
                "operators" to operators,
                "identities" to report["identities"],
                "representationMatrixResult" to
                    report["representationMatrixResult"],
                "intrinsicEvidence" to report["intrinsicEvidence"]
            )
        output["gasParity"] =
            linkedMapOf(
                "status" to
                    if (gasParityPassed) "passed" else "failed",
                "scope" to
                    "same-run-local-composite-exact-gas-evidence",
                "gasMicrofixtures" to gas,
                "counterCoverage" to report["counterCoverage"],
                "gasExhaustionEvidence" to
                    report["gasExhaustionEvidence"],
                "finiteLoopEvidence" to report["finiteLoopEvidence"],
                "ledgerLifecycleEvidence" to
                    report["ledgerLifecycleEvidence"]
            )
        output["hostedBoundaryResults"] =
            linkedMapOf(
                "semanticBoundaryInvocationEvidence" to
                    report["semanticBoundaryInvocationEvidence"],
                "ledgerLifecycleEvidence" to
                    report["ledgerLifecycleEvidence"],
                "hostedLocalLimitCapability" to
                    report["hostedLocalLimitCapability"],
                "cyclicProofUnavailabilityCapability" to
                    report["cyclicProofUnavailabilityCapability"],
                "hostedOutcomes" to report["hostedOutcomes"]
            )
        output["workingDependency"] =
            linkedMapOf(
                "focusedCoordinates" to
                    blueLanguageFocusedCoordinates,
                "languageCommit" to
                    languageFingerprint?.commit,
                "languageWorkspaceSha256" to
                    languageFingerprint?.workspaceSha256,
                "focusedResolution" to focusedResolution,
                "aggregateCompatibility" to
                    aggregateEvidence.toSortedMap(),
                "aggregateCompatibilityOnly" to true,
                "productionLegacyImports" to
                    productionLegacyImports,
                "testLegacyImports" to testLegacyImports
            )
        val outputFile =
            bexWorkingVerificationReport.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(output)) + "\n"
        )
        check(workingReady) {
            "BEX working verification is not ready: " +
                failures.joinToString("; ") +
                ". See " + outputFile
        }
    }
}

listOf(
    tasks.test,
    writeBexConformanceReport,
    sourceReleaseArchive,
    verifyBlueLanguageAggregateCompatibility,
    writeFocusedLanguageResolutionEvidence,
    writeLatestLanguageMigrationBaseline
).forEach { verificationTask ->
    verificationTask.configure {
        mustRunAfter(initializeBexWorkingVerificationReceipt)
    }
}

val bexWorkingVerification by tasks.registering {
    group = "verification"
    description =
        "Runs the complete local-composite BEX working gate without " +
            "requiring a published Language release."
    dependsOn(writeBexWorkingVerificationReport)
}

val bexReleaseVerify by tasks.registering {
    group = "verification"
    description =
        "Runs the strict published/local release matrix and fails closed " +
            "when compatible published Language evidence is unavailable."
    dependsOn(bexReleaseEvidence)
}

tasks.check {
    dependsOn(
        verifyDeterministicArchives,
        binaryApiCheck,
        java8BytecodeCheck,
        benchmarkCompilationCheck
    )
}

val genResourcesDir = layout.buildDirectory.dir("generated-resources")
val generateBuildProperties by tasks.registering {
    val buildPropertiesFile = genResourcesDir.map { it.file("blue/bex/build.properties") }
    val sourceDateEpoch =
        System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull() ?: 0L
    val reproducibleBuildTimestamp =
        Instant.ofEpochSecond(sourceDateEpoch).toString()
    inputs.property("buildTimestamp", reproducibleBuildTimestamp)
    outputs.file(buildPropertiesFile)
    doLast {
        val file = buildPropertiesFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            blue-bex-java.build.version=${project.version}
            blue-bex-java.build.timestamp=$reproducibleBuildTimestamp
            """.trimIndent()
        )
    }
}

sourceSets.main {
    output.dir(genResourcesDir, "builtBy" to generateBuildProperties)
}

tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "blue.bex"
            artifactId = "blue-bex-java"
            from(components["java"])

            pom {
                name.set("Blue BEX Java")
                description.set("Compiled Java engine for Blue Expression Objects.")
                url.set("https://timeline.blue")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/bluecontract/blue-bex-java/blob/main/LICENSE")
                    }
                }
                developers {
                    developer {
                        name.set("Blue")
                        email.set("devsupport@timeline.blue")
                    }
                }
                scm {
                    url.set("https://github.com/bluecontract/blue-bex-java.git")
                    connection.set("scm:git:git@github.com:bluecontract/blue-bex-java.git")
                    developerConnection.set("scm:git:git@github.com:bluecontract/blue-bex-java.git")
                }
            }
        }
    }

    repositories {
        maven {
            url = layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
        }
        if (System.getenv("CI") == null) {
            maven {
                name = "local"
                url = uri("file:///" + File(System.getProperty("user.home"), ".m2/repository").absolutePath)
            }
        }
    }
}

tasks.withType<
    org.gradle.api.publish.maven.tasks.PublishToMavenRepository
>().configureEach {
    dependsOn(bexReleaseVerify)
}
tasks.withType<
    org.gradle.api.publish.maven.tasks.PublishToMavenLocal
>().configureEach {
    dependsOn(bexReleaseVerify)
}
tasks.matching {
    it.name in setOf(
        "jreleaserAnnounce",
        "jreleaserDeploy",
        "jreleaserFullRelease",
        "jreleaserPublish",
        "jreleaserRelease",
        "jreleaserUpload"
    )
}.configureEach {
    dependsOn(bexReleaseVerify)
}

if (System.getenv("CI") != null) {
    jreleaser {
        signing {
            active.set(org.jreleaser.model.Active.ALWAYS)
            armored.set(true)
        }
        project {
            description.set("Compiled Java engine for Blue Expression Objects.")
            copyright.set("Copyright 2026 Blue Company. Licensed under the MIT License")
        }
        deploy {
            maven {
                mavenCentral {
                    create("sonatype") {
                        active.set(org.jreleaser.model.Active.ALWAYS)
                        url.set("https://central.sonatype.com/api/v1/publisher")
                        applyMavenCentralRules.set(true)
                        snapshotSupported.set(true)
                        stagingRepository("build/staging-deploy")
                    }
                }
            }
        }
    }
}

fun determineProjectVersion(): String {
    val tomlFile = file(".cz.toml")
    val baseVersion = if (tomlFile.exists()) {
        Regex("""version\s*=\s*"([^"]+)"""")
            .find(tomlFile.readText())
            ?.groupValues
            ?.get(1)
            ?: "1.0.0"
    } else {
        "1.0.0"
    }
    return baseVersion + if (System.getenv("CI") == null) "-SNAPSHOT" else ""
}

fun sourceReleaseExecutableModes(
    archive: File,
    rootDirectory: String
): Map<String, String> {
    val executablePaths =
        listOf(
            "gradlew",
            ".github/scripts/run-final-publication-gates.sh"
        )
    CommonsZipFile.builder().setFile(archive).get().use { zip ->
        return executablePaths.associateWith { relativePath ->
            val entry =
                zip.getEntry("$rootDirectory/$relativePath")
                    ?: throw GradleException(
                        "Source release is missing executable $relativePath"
                    )
            val permissionBits = entry.unixMode and 0x1ff
            check(permissionBits == 0x1ed) {
                "Source-release executable $relativePath has mode " +
                    permissionBits.toString(8) +
                    ", expected 755"
            }
            permissionBits.toString(8).padStart(4, '0')
        }
    }
}
