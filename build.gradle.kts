import org.gradle.api.artifacts.dsl.LockMode

plugins {
    java
    id("com.gradleup.shadow") version "9.4.2"
}

group = "org.pinnaclesmp"
version = "26.2-3"

java {
    // Paper 26.x uses the newer Paper API versioning and currently documents Java 25 for 26.x builds.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.62-beta")
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    testImplementation("io.papermc.paper:paper-api:26.2.build.62-beta")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    testImplementation("org.mockito:mockito-core:5.23.0")
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
        filesMatching("plugin.yml") {
            expand("version" to project.version)
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
