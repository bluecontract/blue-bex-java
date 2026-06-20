import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    `java-library`
    `maven-publish`
    signing
    id("org.jreleaser") version "1.24.0"
}

group = "blue.bex"
version = determineProjectVersion()

base {
    archivesName.set("blue-bex-java")
}

repositories {
    if (System.getenv("CI") == null) {
        mavenLocal()
    }
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

dependencies {
    api("blue.language:blue-language-java:3.0.0")

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
        junitXml.required.set(false)
        html.required.set(true)
    }
    testLogging {
        events("PASSED", "FAILED", "SKIPPED")
        showStandardStreams = true
    }
}

val genResourcesDir = layout.buildDirectory.dir("generated-resources")
val generateBuildProperties by tasks.registering {
    val buildPropertiesFile = genResourcesDir.map { it.file("blue/bex/build.properties") }
    outputs.file(buildPropertiesFile)
    doLast {
        val file = buildPropertiesFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            blue-bex-java.build.version=${project.version}
            blue-bex-java.build.timestamp=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(Date())}
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
