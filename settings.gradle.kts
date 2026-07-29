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
    includeBuild(compositeDirectory) {
        dependencySubstitution {
            substitute(module("blue.language:blue-language-java"))
                .using(project(":"))
        }
    }
}
