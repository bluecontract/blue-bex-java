plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "blue-bex-java"

val blueLanguageCompositePath =
    providers.gradleProperty("blueLanguageCompositePath")
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

if (blueLanguageCompositePath != null) {
    val compositeDirectory = file(blueLanguageCompositePath)
    require(compositeDirectory.isDirectory) {
        "blueLanguageCompositePath is not a directory: " +
            compositeDirectory.absolutePath
    }
    require(
        file("${compositeDirectory.path}/settings.gradle.kts").isFile ||
            file("${compositeDirectory.path}/settings.gradle").isFile
    ) {
        "blueLanguageCompositePath is not a Gradle build: " +
            compositeDirectory.absolutePath
    }
    val requiredLanguageProjects =
        listOf(
            "blue-language-model",
            "blue-language-core",
            "blue-language-mapping",
            "blue-language-ipfs",
            "blue-contracts-core",
            "blue-conformance",
            "blue-language-java"
        )
    val missingLanguageProjects =
        requiredLanguageProjects.filter { projectName ->
            val projectDirectory =
                file("${compositeDirectory.path}/$projectName")
            !projectDirectory.isDirectory ||
                (!file("${projectDirectory.path}/build.gradle.kts").isFile &&
                    !file("${projectDirectory.path}/build.gradle").isFile)
        }
    require(missingLanguageProjects.isEmpty()) {
        "blueLanguageCompositePath does not contain the required Gradle " +
            "subprojects: " + missingLanguageProjects.joinToString(", ")
    }
    includeBuild(compositeDirectory) {
        dependencySubstitution {
            substitute(module("blue.language:blue-language-model"))
                .using(project(":blue-language-model"))
            substitute(module("blue.language:blue-language-core"))
                .using(project(":blue-language-core"))
            substitute(module("blue.language:blue-language-mapping"))
                .using(project(":blue-language-mapping"))
            substitute(module("blue.language:blue-language-ipfs"))
                .using(project(":blue-language-ipfs"))
            substitute(module("blue.language:blue-contracts-core"))
                .using(project(":blue-contracts-core"))
            substitute(module("blue.language:blue-conformance"))
                .using(project(":blue-conformance"))
            substitute(module("blue.language:blue-language-java"))
                .using(project(":blue-language-java"))
        }
    }
}
