import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Jar
import java.security.MessageDigest

plugins {
    id("blue.bex.conformance")
    id("blue.bex.language-dependencies")
    id("blue.bex.api-evidence")
    id("blue.bex.jmh")
}

description = "BEX fixtures, package integrity, properties, and release evidence"

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
            && ledger.contains("\"removedDescriptorLines\": ${removed.size}")
            && ledger.contains("\"addedDescriptorLines\": ${added.size}")) {
            "Migration ledger does not authenticate the complete API delta"
        }
    }
}

tasks.named("bexApiEvidence") {
    dependsOn(binaryApiCheck)
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
        tasks.named("writeLanguageDependencyEvidence")
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
