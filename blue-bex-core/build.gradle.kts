plugins {
    id("blue.bex.java8-library")
    id("blue.bex.language-dependencies")
    id("blue.bex.reproducible-archives")
    id("blue.bex.publication")
}

description = "Host-neutral Blue Expression Object compiler and runtime"

base {
    archivesName.set("blue-bex-core")
}

val languageVersion = extensions
    .getByType<blue.bex.buildlogic.LanguageDependencyModeExtension>()
    .version.get()

dependencies {
    api("blue.language:blue-language-model:$languageVersion")
    api("blue.language:blue-language-core:$languageVersion")
}
