import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    java
    id("io.papermc.paperweight.userdev") version "1.7.4"
}

group = "com.eternalworlds"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Provides full NMS access (ServerPlayer, packets, etc.) + Paper API.
    // Gradle downloads the dev bundle automatically — no manual jar needed.
    paperweight.paperDevBundle("1.21.1-R0.1-SNAPSHOT")
}

// Paper 1.20.5+ uses Mojang mappings natively at runtime.
// MOJANG_PRODUCTION skips reobfuscation and marks the jar with the correct
// paperweight-mappings-namespace manifest entry.
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    javadoc {
        options.encoding = "UTF-8"
    }
    processResources {
        // version is hardcoded in plugin.yml — no template expansion needed
    }
}
