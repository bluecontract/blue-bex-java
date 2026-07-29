import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile
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
val blueLanguageDeclaredCoordinate =
    "blue.language:blue-language-java:$blueLanguagePublishedVersion"
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

dependencies {
    api(blueLanguageDeclaredCoordinate)

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.yaml:snakeyaml:1.31")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
val invalidateCleanBuildArtifactEvidence by tasks.registering {
    group = "verification"
    description =
        "Invalidates any prior clean-build receipt before artifact work starts."
    outputs.upToDateWhen { false }
    doLast {
        cleanBuildArtifactEvidence.get().asFile.delete()
        cleanBuildDependencyArtifact.get().asFile.delete()
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
val writeCleanBuildArtifactHashes by tasks.registering {
    group = "verification"
    description =
        "Records all four release hashes from one clean committed checkout."
    dependsOn(
        invalidateCleanBuildArtifactEvidence,
        mainJar,
        sourcesJarTask,
        javadocJarTask,
        sourceReleaseArchive
    )
    outputs.file(cleanBuildArtifactEvidence)
    outputs.file(cleanBuildDependencyArtifact)
    outputs.upToDateWhen { false }
    doFirst {
        cleanBuildArtifactEvidence.get().asFile.delete()
        cleanBuildDependencyArtifact.get().asFile.delete()
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
            configurations.compileClasspath.get()
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
        val verifierLanguageArtifacts =
            configurations.compileClasspath.get()
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
        "Resolves blue-language-java and verifies standalone artifacts against recorded Maven Central provenance."
    outputs.file(dependencyResolutionEvidence)
    outputs.upToDateWhen { false }
    doFirst {
        dependencyResolutionEvidence.get().asFile.delete()
    }
    doLast {
        val matches =
            configurations.compileClasspath.get()
                .resolvedConfiguration
                .resolvedArtifacts
                .filter {
                    it.moduleVersion.id.group == "blue.language" &&
                        it.name == "blue-language-java" &&
                        it.extension == "jar"
                }
        check(matches.size == 1) {
            "Expected exactly one blue-language-java compile artifact, found " +
                matches.joinToString { it.file.absolutePath }
        }
        val artifact = matches.single()
        val component = artifact.id.componentIdentifier
        val compositeDirectory =
            blueLanguageCompositePath
                ?.let { file(it).canonicalFile }
        val artifactHash = sha256(artifact.file)
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
            moduleVersionCacheAcceptance =
                if (!blueLanguageRequireFreshModuleCache.get()) {
                    "not-required-for-current-run"
                } else if (blueLanguageModuleVersionCacheInitiallyAbsent) {
                    "passed"
                } else {
                    "failed"
                }
        } else {
            provenanceStatus = "not-applicable-local-composite"
            moduleVersionCacheAcceptance = "not-executed"
        }
        writeEvidence(
            dependencyResolutionEvidence.get().asFile,
            mapOf(
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
                    "standalone-published-blue-language-module-version-cache"
            )
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

tasks.test {
    finalizedBy(writeBexConformanceReport)
}

tasks.register("bexConformanceReport") {
    group = "verification"
    description = "Runs all tests and produces the machine-readable BEX 2.0 conformance report."
    dependsOn(tasks.test)
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
    dependsOn(bexReleaseEvidence)
}
tasks.withType<
    org.gradle.api.publish.maven.tasks.PublishToMavenLocal
>().configureEach {
    dependsOn(bexReleaseEvidence)
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
    dependsOn(bexReleaseEvidence)
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
