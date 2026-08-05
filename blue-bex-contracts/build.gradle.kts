plugins {
    id("blue.bex.java8-library")
    id("blue.bex.language-dependencies")
    id("blue.bex.reproducible-archives")
    id("blue.bex.publication")
}

description = "Contracts-hosted adapters for the Blue BEX runtime"

base {
    archivesName.set("blue-bex-contracts")
}

val languageVersion = extensions
    .getByType<blue.bex.buildlogic.LanguageDependencyModeExtension>()
    .version.get()

dependencies {
    api(project(":blue-bex-core"))
    api("blue.language:blue-contracts-core:$languageVersion")
}
