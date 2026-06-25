plugins {
    java
    id("com.gradleup.shadow") version "9.4.2"
}

group = "org.pinnaclesmp"
version = "1.0.1"

java {
    // Paper 26.x uses the newer Paper API versioning and currently documents Java 25 for 26.x builds.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.slf4j:slf4j-nop:2.0.16")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
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
