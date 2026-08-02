plugins {
    id("blue.bex.java8-library")
    id("blue.bex.language-dependencies")
    id("blue.bex.reproducible-archives")
    id("blue.bex.publication")
}

description = "One-coordinate aggregate for Blue BEX core and Contracts hosting"

base {
    archivesName.set("blue-bex-java")
}

dependencies {
    api(project(":blue-bex-core"))
    api(project(":blue-bex-contracts"))
}

tasks.named<blue.bex.buildlogic.tasks.VerifyJava8BytecodeTask>(
    "java8BytecodeCheck") {
    allowEmpty.set(true)
}
