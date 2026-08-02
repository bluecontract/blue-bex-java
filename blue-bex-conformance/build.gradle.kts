import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.javadoc.Javadoc
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.jar.JarFile

plugins {
    id("blue.bex.conformance")
    id("blue.bex.language-dependencies")
    id("blue.bex.api-evidence")
    id("blue.bex.jmh")
}

description = "BEX fixtures, package integrity, properties, and release evidence"

// Release receipts consume core archive task providers during this project's
// configuration; register those providers before resolving them below.
evaluationDependsOn(":blue-bex-core")

fun sha256Of(file: File): String =
    MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

fun Project.rootRelativePath(file: File): String {
    val root = rootProject.projectDir.toPath().toAbsolutePath().normalize()
    val target = file.toPath().toAbsolutePath().normalize()
    check(target.startsWith(root)) {
        "Evidence artifact is outside the BEX checkout: $target"
    }
    return root.relativize(target).toString().replace(File.separatorChar, '/')
}

fun writeEvidenceReceipt(file: File, values: Map<String, String>) {
    values.forEach { (key, value) ->
        check(key.isNotBlank() && !key.contains('=') && !key.contains('\n')) {
            "Invalid evidence key: $key"
        }
        check(!value.contains('\n') && !value.contains('\r')) {
            "Invalid multiline evidence value for $key"
        }
    }
    file.parentFile.mkdirs()
    file.writeText(
        values.toSortedMap().entries.joinToString(
            separator = "\n",
            postfix = "\n") { (key, value) -> "$key=$value" },
        StandardCharsets.UTF_8)
}

val languageVersion = extensions
    .getByType<blue.bex.buildlogic.LanguageDependencyModeExtension>()
    .version.get()

dependencies {
    testImplementation(project(":blue-bex-core"))
    testImplementation(project(":blue-bex-contracts"))
    testImplementation("blue.language:blue-language-model:$languageVersion")
    testImplementation("blue.language:blue-language-core:$languageVersion")
    testImplementation("blue.language:blue-contracts-core:$languageVersion")
    // Explicit aggregate compatibility smoke and publication provenance.
    testRuntimeOnly("blue.language:blue-language-java:$languageVersion")
    testImplementation("org.yaml:snakeyaml:1.31")
}

tasks.named<blue.bex.buildlogic.tasks.GenerateDependencyEvidenceTask>(
    "writeLanguageDependencyEvidence") {
    val evidenceRuntime = configurations.testRuntimeClasspath.get()
    artifacts.setFrom(evidenceRuntime)
    resolvedComponents.set(provider {
        evidenceRuntime.incoming.resolutionResult.allComponents
            .map { it.id.displayName }
            .sorted()
    })
}

sourceSets {
    test {
        java.setSrcDirs(listOf(rootProject.file("src/test/java")))
        resources.setSrcDirs(listOf(rootProject.file("src/test/resources")))
    }
}

tasks.test {
    workingDir = rootProject.projectDir
}

tasks.named<blue.bex.buildlogic.tasks.VerifyJava8BytecodeTask>(
    "java8BytecodeCheck") {
    allowEmpty.set(true)
}

val apiInspectionJar = tasks.register<Jar>("apiInspectionJar") {
    group = "verification"
    archiveClassifier.set("api-inspection")
    dependsOn(
        project(":blue-bex-core").tasks.named("jar"),
        project(":blue-bex-contracts").tasks.named("jar")
    )
    from({
        zipTree(project(":blue-bex-core").tasks.named<Jar>("jar").get()
            .archiveFile.get().asFile)
    })
    from({
        zipTree(project(":blue-bex-contracts").tasks.named<Jar>("jar").get()
            .archiveFile.get().asFile)
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val generateBinaryApiManifest = tasks.register<JavaExec>(
    "generateBinaryApiManifest") {
    group = "verification"
    dependsOn(tasks.named("testClasses"), apiInspectionJar)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("blue.bex.conformance.BexBinaryApiManifestMain")
    doFirst {
        setArgs(listOf(
            apiInspectionJar.get().archiveFile.get().asFile.absolutePath,
            layout.buildDirectory.file("reports/bex-release/public-api.txt")
                .get().asFile.absolutePath
        ))
    }
}

val binaryApiCheck = tasks.register("binaryApiCheck") {
    group = "verification"
    dependsOn(generateBinaryApiManifest, "generateApiClassification")
    val generated = layout.buildDirectory.file(
        "reports/bex-release/public-api.txt")
    val generatedClassification = layout.buildDirectory.file(
        "reports/bex-release/public-api-classification.json")
    val required = rootProject.layout.projectDirectory.file(
        "src/test/resources/hosted-release/required-public-api.txt")
    val checkpoint = rootProject.layout.projectDirectory.file(
        "gradle/verification/api/working-checkpoint-public-api.txt")
    val checkpointClassification = rootProject.layout.projectDirectory.file(
        "gradle/verification/api/working-checkpoint-public-api-classification.json")
    val reviewedClassification = rootProject.layout.projectDirectory.file(
        "docs/public-api-classification.json")
    val removedDescriptors = rootProject.layout.projectDirectory.file(
        "gradle/verification/api/modernization-removed-descriptors.txt")
    val addedDescriptors = rootProject.layout.projectDirectory.file(
        "gradle/verification/api/modernization-added-descriptors.txt")
    val migrationLedger = rootProject.layout.projectDirectory.file(
        "docs/latest-language-api-migration.json")
    inputs.files(
        generated,
        generatedClassification,
        required,
        checkpoint,
        checkpointClassification,
        reviewedClassification,
        removedDescriptors,
        addedDescriptors,
        migrationLedger
    )
    doLast {
        check(generated.get().asFile.readBytes()
            .contentEquals(required.asFile.readBytes())) {
            "Public API differs from the reviewed baseline; regenerate only " +
                "with an exact migration-ledger update"
        }
        check(generatedClassification.get().asFile.readBytes()
            .contentEquals(reviewedClassification.asFile.readBytes())) {
            "Public API classification differs from same-run generation"
        }
        fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
        fun ownedDescriptors(file: File): java.util.SortedSet<String> {
            val descriptors = sortedSetOf<String>()
            var owner = ""
            file.forEachLine { line ->
                when {
                    line.startsWith("class ") -> {
                        owner = line
                        descriptors.add(line)
                    }
                    line.startsWith("  ") -> {
                        check(owner.isNotEmpty()) {
                            "Member descriptor appears before its owner in $file"
                        }
                        descriptors.add("$owner :: ${line.substring(2)}")
                    }
                }
            }
            return descriptors
        }
        val beforeLines = ownedDescriptors(checkpoint.asFile)
        val afterLines = ownedDescriptors(required.asFile)
        val afterTypeCount = required.asFile.readLines()
            .count { it.startsWith("class ") }
        val afterDescriptorCount = required.asFile.readLines()
            .count { it.startsWith("class ") || it.startsWith("  ") }
        val removed = (beforeLines - afterLines).toList()
        val added = (afterLines - beforeLines).toList()
        check(removed == removedDescriptors.asFile.readLines()) {
            "Complete removed-descriptor ledger is stale"
        }
        check(added == addedDescriptors.asFile.readLines()) {
            "Complete added-descriptor ledger is stale"
        }
        val ledger = migrationLedger.asFile.readText()
        val checkpointHash = sha256(checkpoint.asFile)
        val afterHash = sha256(required.asFile)
        check(checkpointClassification.asFile.readText()
            .contains(checkpointHash)) {
            "Checkpoint classification is not bound to its manifest"
        }
        check(ledger.contains(checkpointHash)
            && ledger.contains(afterHash)
            && ledger.contains(sha256(removedDescriptors.asFile))
            && ledger.contains(sha256(addedDescriptors.asFile))
            && ledger.contains("\"publicTypeCount\": $afterTypeCount")
            && ledger.contains(
                "\"publicDescriptorCount\": $afterDescriptorCount")
            && ledger.contains("\"removedDescriptorLines\": ${removed.size}")
            && ledger.contains("\"addedDescriptorLines\": ${added.size}")) {
            "Migration ledger does not authenticate the complete API delta"
        }
    }
}

val binaryApiEvidenceReceipt = layout.buildDirectory.file(
    "reports/bex-release/binary-api.properties")
val requiredApiManifest = rootProject.layout.projectDirectory.file(
    "src/test/resources/hosted-release/required-public-api.txt")
val writeBinaryApiEvidence = tasks.register(
    "writeBinaryApiEvidence") {
    group = "verification"
    description = "Writes hashes for the verified packaged binary API."
    dependsOn(binaryApiCheck)
    inputs.files(
        apiInspectionJar.flatMap(Jar::getArchiveFile),
        layout.buildDirectory.file("reports/bex-release/public-api.txt"),
        requiredApiManifest)
    outputs.file(binaryApiEvidenceReceipt)
    outputs.upToDateWhen { false }
    doLast {
        val artifact = apiInspectionJar.get().archiveFile.get().asFile
        val manifest = layout.buildDirectory.file(
            "reports/bex-release/public-api.txt").get().asFile
        val required = requiredApiManifest.asFile
        check(artifact.isFile) { "API inspection JAR is missing: $artifact" }
        check(manifest.isFile) { "Generated API manifest is missing: $manifest" }
        check(required.isFile) { "Required API manifest is missing: $required" }

        val manifestLines = manifest.readLines(StandardCharsets.UTF_8)
        val requiredLines = required.readLines(StandardCharsets.UTF_8)
        check(manifestLines.isNotEmpty()
            && manifestLines.first()
                == "schema=blue-bex-binary-api-manifest/1.0") {
            "Generated API manifest has no recognized schema"
        }
        check(manifestLines == requiredLines) {
            "Generated and required public API manifests differ"
        }

        writeEvidenceReceipt(
            binaryApiEvidenceReceipt.get().asFile,
            linkedMapOf(
                "schema" to "blue-bex-binary-api-evidence/1.0",
                "status" to "passed",
                "verificationTask" to
                    ":blue-bex-conformance:binaryApiCheck",
                "verificationTaskStatus" to "passed",
                "artifact.path" to rootProject.rootRelativePath(artifact),
                "artifact.sha256" to sha256Of(artifact),
                "manifest.path" to rootProject.rootRelativePath(manifest),
                "manifest.sha256" to sha256Of(manifest),
                "manifest.schema" to
                    "blue-bex-binary-api-manifest/1.0",
                "required.path" to rootProject.rootRelativePath(required),
                "required.sha256" to sha256Of(required),
                "required.comparison" to "exact-match",
                "required.signatureCount" to requiredLines.size.toString(),
                "required.missingCount" to "0",
                "required.unexpectedCount" to "0"))
    }
}

val jmhSourceSet = sourceSets.named("jmh")
val benchmarkCompilationEvidenceReceipt = layout.buildDirectory.file(
    "reports/bex-release/benchmark-compilation.properties")
val writeBenchmarkCompilationEvidence = tasks.register(
    "writeBenchmarkCompilationEvidence") {
    group = "verification"
    description = "Writes evidence from the actual compiled JMH source set."
    dependsOn(tasks.named("jmhClasses"))
    outputs.file(benchmarkCompilationEvidenceReceipt)
    outputs.upToDateWhen { false }
    doLast {
        val source = file(
            "src/jmh/java/blue/bex/benchmark/BexCoreBenchmark.java")
        val relativeClass = "blue/bex/benchmark/BexCoreBenchmark.class"
        val candidates = jmhSourceSet.get().output.classesDirs.files
            .map { directory -> File(directory, relativeClass) }
            .filter(File::isFile)
        check(source.isFile) { "Benchmark source is missing: $source" }
        check(candidates.size == 1) {
            "Expected one compiled BexCoreBenchmark class, found $candidates"
        }
        val compiledClass = candidates.single()
        val allSources = fileTree("src/jmh/java") {
            include("**/*.java")
        }.files
        val allClasses = jmhSourceSet.get().output.classesDirs.files
            .flatMap { directory ->
                fileTree(directory) { include("**/*.class") }.files
            }
        check(allSources.isNotEmpty() && allClasses.isNotEmpty()) {
            "JMH compilation produced no source/class evidence"
        }

        writeEvidenceReceipt(
            benchmarkCompilationEvidenceReceipt.get().asFile,
            linkedMapOf(
                "schema" to
                    "blue-bex-benchmark-compilation-evidence/1.0",
                "status" to "passed",
                "timingExecuted" to "false",
                "source.path" to rootProject.rootRelativePath(source),
                "source.sha256" to sha256Of(source),
                "class.path" to
                    rootProject.rootRelativePath(compiledClass),
                "class.sha256" to sha256Of(compiledClass),
                "sourceCount" to allSources.size.toString(),
                "classCount" to allClasses.size.toString()))
    }
}

val java8BytecodeEvidenceReceipt = layout.buildDirectory.file(
    "reports/bex-release/java8-bytecode.properties")
val writeJava8BytecodeEvidence = tasks.register(
    "writeJava8BytecodeEvidence") {
    group = "verification"
    description = "Verifies and records packaged Java 8 classfile evidence."
    dependsOn(
        apiInspectionJar,
        project(":blue-bex-core").tasks.named("java8BytecodeCheck"),
        project(":blue-bex-contracts").tasks.named("java8BytecodeCheck"))
    inputs.file(apiInspectionJar.flatMap(Jar::getArchiveFile))
    outputs.file(java8BytecodeEvidenceReceipt)
    outputs.upToDateWhen { false }
    doLast {
        val artifact = apiInspectionJar.get().archiveFile.get().asFile
        check(artifact.isFile) { "Packaged API inspection JAR is missing" }
        var classCount = 0
        val observedMajors = sortedSetOf<Int>()
        JarFile(artifact).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".class")) {
                    continue
                }
                val header = ByteArray(8)
                var offset = 0
                jar.getInputStream(entry).use { input ->
                    while (offset < header.size) {
                        val read = input.read(
                            header, offset, header.size - offset)
                        check(read >= 0) {
                            "Truncated classfile in $artifact: ${entry.name}"
                        }
                        offset += read
                    }
                }
                val validMagic =
                    (header[0].toInt() and 0xff) == 0xca
                        && (header[1].toInt() and 0xff) == 0xfe
                        && (header[2].toInt() and 0xff) == 0xba
                        && (header[3].toInt() and 0xff) == 0xbe
                check(validMagic) {
                    "Invalid classfile magic in $artifact: ${entry.name}"
                }
                val major = ((header[6].toInt() and 0xff) shl 8) or
                    (header[7].toInt() and 0xff)
                observedMajors.add(major)
                classCount++
            }
        }
        check(classCount > 0) { "No packaged production classes were verified" }
        check(observedMajors == sortedSetOf(52)) {
            "Packaged classes are not uniformly Java 8: $observedMajors"
        }

        writeEvidenceReceipt(
            java8BytecodeEvidenceReceipt.get().asFile,
            linkedMapOf(
                "schema" to "blue-bex-java8-bytecode-evidence/1.0",
                "status" to "passed",
                "artifact.path" to rootProject.rootRelativePath(artifact),
                "artifact.sha256" to sha256Of(artifact),
                "classCount" to classCount.toString(),
                "expected.magic" to "CAFEBABE",
                "observed.magic" to "CAFEBABE",
                "expected.major" to "52",
                "observed.major" to observedMajors.single().toString()))
    }
}

val archiveEvidenceProject = project(":blue-bex-core")
val archiveEvidenceJar = archiveEvidenceProject.tasks.named<Jar>("jar")
val archiveEvidenceReplicaJar =
    archiveEvidenceProject.tasks.named<Jar>("replicaJar")
val archiveEvidenceSourcesJar =
    archiveEvidenceProject.tasks.named<Jar>("sourcesJar")
val archiveEvidenceReplicaSourcesJar =
    archiveEvidenceProject.tasks.named<Jar>("replicaSourcesJar")
val archiveEvidenceJavadocJar =
    archiveEvidenceProject.tasks.named<Jar>("javadocJar")
val archiveEvidenceJavadoc =
    archiveEvidenceProject.tasks.named<Javadoc>("javadoc")
val archiveEvidenceReplicaJavadocJar =
    archiveEvidenceProject.tasks.named<Jar>("replicaJavadocJar")
val sourceReleaseArchive =
    rootProject.tasks.named<Zip>("sourceReleaseArchive")
val replicaSourceReleaseArchive =
    rootProject.tasks.named<Zip>("replicaSourceReleaseArchive")

archiveEvidenceJavadoc.configure {
    outputs.upToDateWhen { false }
}
archiveEvidenceReplicaJavadocJar.configure {
    outputs.upToDateWhen { false }
}
replicaSourceReleaseArchive.configure {
    outputs.upToDateWhen { false }
}

val deterministicArchiveEvidenceReceipt = layout.buildDirectory.file(
    "reports/bex-release/deterministic-archives.properties")
val writeDeterministicArchiveEvidence = tasks.register(
    "writeDeterministicArchiveEvidence") {
    group = "verification"
    description = "Writes evidence from byte-compared archive replicas."
    dependsOn(
        archiveEvidenceProject.tasks.named("verifyReproducibleArchives"),
        rootProject.tasks.named("verifySourceReleaseArchiveReproducibility"))
    inputs.files(
        archiveEvidenceJar.flatMap(Jar::getArchiveFile),
        archiveEvidenceReplicaJar.flatMap(Jar::getArchiveFile),
        archiveEvidenceSourcesJar.flatMap(Jar::getArchiveFile),
        archiveEvidenceReplicaSourcesJar.flatMap(Jar::getArchiveFile),
        archiveEvidenceJavadocJar.flatMap(Jar::getArchiveFile),
        archiveEvidenceReplicaJavadocJar.flatMap(Jar::getArchiveFile),
        sourceReleaseArchive.flatMap(Zip::getArchiveFile),
        replicaSourceReleaseArchive.flatMap(Zip::getArchiveFile))
    outputs.file(deterministicArchiveEvidenceReceipt)
    outputs.upToDateWhen { false }
    doLast {
        val archives = linkedMapOf(
            "main.original" to archiveEvidenceJar.get()
                .archiveFile.get().asFile,
            "main.rebuild" to archiveEvidenceReplicaJar.get()
                .archiveFile.get().asFile,
            "sources.original" to archiveEvidenceSourcesJar.get()
                .archiveFile.get().asFile,
            "sources.rebuild" to archiveEvidenceReplicaSourcesJar.get()
                .archiveFile.get().asFile,
            "javadoc.original" to archiveEvidenceJavadocJar.get()
                .archiveFile.get().asFile,
            "javadoc.rebuild" to archiveEvidenceReplicaJavadocJar.get()
                .archiveFile.get().asFile,
            "sourceRelease.original" to sourceReleaseArchive.get()
                .archiveFile.get().asFile,
            "sourceRelease.replica" to replicaSourceReleaseArchive.get()
                .archiveFile.get().asFile)
        archives.forEach { (name, archive) ->
            check(archive.isFile) { "$name archive is missing: $archive" }
        }
        fun byteIdentical(first: String, second: String): Boolean =
            archives.getValue(first).readBytes().contentEquals(
                archives.getValue(second).readBytes())
        check(byteIdentical("main.original", "main.rebuild")) {
            "Main JAR replica is not byte-identical"
        }
        check(byteIdentical("sources.original", "sources.rebuild")) {
            "Sources JAR replica is not byte-identical"
        }
        check(byteIdentical("javadoc.original", "javadoc.rebuild")) {
            "Javadoc JAR replica is not byte-identical"
        }
        check(byteIdentical(
            "sourceRelease.original", "sourceRelease.replica")) {
            "Source release replica is not byte-identical"
        }
        val javadocFreshlyRegenerated =
            archiveEvidenceJavadoc.get().state.didWork
        val independentSourceAssembly =
            replicaSourceReleaseArchive.get().state.didWork
        check(javadocFreshlyRegenerated) {
            "Javadoc replica was not freshly regenerated"
        }
        check(independentSourceAssembly) {
            "Source release replica was not independently assembled"
        }

        val receipt = linkedMapOf(
            "schema" to "blue-bex-deterministic-archives-evidence/1.0",
            "status" to "passed",
            "scope" to
                "jar-packaging-determinism-and-source-release-reassembly-from-the-same-working-tree",
            "independentCleanCompilation" to "false",
            "javadoc.freshlyRegenerated" to
                javadocFreshlyRegenerated.toString(),
            "sourceRelease.byteIdentity" to "true",
            "sourceRelease.hashIdentity" to "true",
            "sourceRelease.independentAssembly" to
                independentSourceAssembly.toString(),
            "sourceRelease.independentCleanCheckout" to "false")
        archives.forEach { (name, archive) ->
            receipt["$name.path"] = rootProject.rootRelativePath(archive)
            receipt["$name.sha256"] = sha256Of(archive)
        }
        writeEvidenceReceipt(
            deterministicArchiveEvidenceReceipt.get().asFile,
            receipt)
    }
}

tasks.named("bexApiEvidence") {
    dependsOn(binaryApiCheck, writeBinaryApiEvidence)
}

tasks.check {
    dependsOn(binaryApiCheck)
}

val syncConformanceEvidenceArtifacts = tasks.register<Copy>(
    "syncConformanceEvidenceArtifacts") {
    group = "verification"
    dependsOn(
        project(":blue-bex-java").tasks.named("assemble"),
        rootProject.tasks.named("sourceReleaseArchive")
    )
    into(layout.buildDirectory.dir("conformance-artifacts"))
    from(project(":blue-bex-java").layout.buildDirectory.dir("libs")) {
        include("*.jar")
        into("libs")
    }
    from(rootProject.layout.buildDirectory.dir("distributions")) {
        include("*-source-release.zip")
        into("distributions")
    }
}

val writeBexConformanceReport = tasks.register<JavaExec>(
    "writeBexConformanceReport") {
    group = "verification"
    description = "Writes same-run BEX test, fixture, gas, and release evidence."
    dependsOn(
        tasks.test,
        syncConformanceEvidenceArtifacts,
        tasks.named("writeLanguageDependencyEvidence"),
        writeDeterministicArchiveEvidence,
        writeBinaryApiEvidence,
        writeBenchmarkCompilationEvidence,
        writeJava8BytecodeEvidence
    )
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("blue.bex.conformance.BexConformanceReportMain")
    val composite = providers.gradleProperty("blueLanguageCompositePath")
        .orElse("")
    doFirst {
        val compositePath = composite.get()
        setArgs(listOf(
            rootProject.projectDir.absolutePath,
            layout.buildDirectory.get().asFile.absolutePath,
            gradle.gradleVersion,
            project.version.toString(),
            if (compositePath.isBlank())
                "standalone-published" else "local-composite",
            "blue.language:blue-language-java:$languageVersion",
            rootProject.layout.projectDirectory.dir(
                ".gradle/bex-hosted-release").asFile.absolutePath,
            compositePath,
            layout.buildDirectory.dir("conformance-artifacts")
                .get().asFile.absolutePath
        ))
    }
}

tasks.register("bexConformanceReport") {
    group = "verification"
    dependsOn(writeBexConformanceReport)
}

tasks.named("bexConformance") {
    dependsOn(writeBexConformanceReport)
}
