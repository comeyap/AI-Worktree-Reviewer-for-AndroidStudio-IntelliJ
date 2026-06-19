plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.github.developer"
version = "1.0.0"

repositories {
    mavenCentral()
}

// Configure IntelliJ platform plugin for Android Studio compatibility
intellij {
    // We target 2023.3 (highly stable, supports 'git4idea' dependency cleanly and is fully backward/forward compatible)
    version.set("2023.3") 
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
