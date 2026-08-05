plugins {
    application
    id("blue.bex.java8-library")
    id("blue.bex.language-dependencies")
}

description = "Compile-tested standalone and Contracts-hosted BEX examples"

dependencies {
    implementation(project(":blue-bex-java"))
}

application {
    mainClass.set("blue.bex.examples.StandaloneBexExample")
}

tasks.register<JavaExec>("hostedConsumerSmoke") {
    group = "verification"
    description = "Compiles and runs a generic Contracts-hosted BEX consumer."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("blue.bex.examples.HostedConsumerSmoke")
}

tasks.check {
    dependsOn("hostedConsumerSmoke")
}
