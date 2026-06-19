plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij") version "1.16.1"
}

group = "com.github.developer"
version = "1.0.0"

repositories {
    mavenCentral()
}

// Configure IntelliJ platform plugin for Android Studio compatibility
intellij {
    // Standard targeting: Match the IntelliJ platform version your Android Studio is built on.
    // e.g., Android Studio Koala (2024.1.1) is based on IntelliJ 2024.1, Ladybug (2024.2.1) is 2024.2.
    version.set("2024.1") 
    type.set("IC")        // Community Edition (fully compatible with Android Studio)
    plugins.set(listOf("git4idea", "platform-images"))
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("242.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
