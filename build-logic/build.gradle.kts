plugins {
    `java-gradle-plugin`
}

group = "blue.bex.buildlogic"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation("me.champeau.jmh:me.champeau.jmh.gradle.plugin:0.7.3")
    implementation("org.jreleaser:org.jreleaser.gradle.plugin:1.24.0")
}

gradlePlugin {
    plugins {
        register("java8Library") {
            id = "blue.bex.java8-library"
            implementationClass =
                "blue.bex.buildlogic.Java8LibraryConventionsPlugin"
        }
        register("languageDependencies") {
            id = "blue.bex.language-dependencies"
            implementationClass =
                "blue.bex.buildlogic.LanguageDependencyModePlugin"
        }
        register("conformance") {
            id = "blue.bex.conformance"
            implementationClass =
                "blue.bex.buildlogic.ConformanceConventionsPlugin"
        }
        register("apiEvidence") {
            id = "blue.bex.api-evidence"
            implementationClass =
                "blue.bex.buildlogic.ApiEvidencePlugin"
        }
        register("reproducibleArchives") {
            id = "blue.bex.reproducible-archives"
            implementationClass =
                "blue.bex.buildlogic.ReproducibleArchivesPlugin"
        }
        register("releaseEvidence") {
            id = "blue.bex.release-evidence"
            implementationClass =
                "blue.bex.buildlogic.ReleaseEvidencePlugin"
        }
        register("architecture") {
            id = "blue.bex.architecture"
            implementationClass =
                "blue.bex.buildlogic.ArchitectureVerificationPlugin"
        }
        register("jmh") {
            id = "blue.bex.jmh"
            implementationClass =
                "blue.bex.buildlogic.JmhConventionsPlugin"
        }
        register("publication") {
            id = "blue.bex.publication"
            implementationClass =
                "blue.bex.buildlogic.PublicationConventionsPlugin"
        }
        register("rootOrchestration") {
            id = "blue.bex.root-orchestration"
            implementationClass =
                "blue.bex.buildlogic.RootOrchestrationPlugin"
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}
