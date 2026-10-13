import org.gradle.api.artifacts.dsl.LockMode

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "org.pinnaclesmp"
version = "26.3-1.2.1"

// Capture the version during configuration; resource copying must not access Task.project.
val pluginVersion = version.toString()

java {
    // Paper 26.x uses the newer Paper API versioning and currently documents Java 25 for 26.x builds.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.177-stable")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.slf4j:slf4j-nop:2.0.20")

    testImplementation("io.papermc.paper:paper-api:26.3.build.177-stable")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    testImplementation("org.mockito:mockito-core:5.24.0")
}

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
    }

    processResources {
        filteringCharset = "UTF-8"
        inputs.property("pluginVersion", pluginVersion)
        filesMatching("plugin.yml") {
            expand("version" to pluginVersion)
        }
    }

    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
    }

    build {
        dependsOn(shadowJar)
    }
}
