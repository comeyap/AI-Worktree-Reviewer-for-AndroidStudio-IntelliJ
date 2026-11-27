import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "com.github.comeyap"
version = "1.1.3"

kotlin {
    jvmToolchain(17)
    compilerOptions.jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1") {
            useInstaller = true
        }
        bundledPlugin("Git4Idea")
    }

    // The git layer and the graph layout are free of platform types, so they run
    // as ordinary JVM tests — no IDE sandbox needed.
    // Match the Kotlin runtime provided by the oldest supported IDE.
    testImplementation(kotlin("test-junit5", "1.9.22"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        id.set("com.github.comeyap.aiworktreereviewer")
        name.set("AI Worktree Reviewer")
        version.set(project.version.toString())

        ideaVersion {
            sinceBuild.set("241")
            untilBuild.set(provider { null })
        }
    }
    
    signing {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }
    
    publishing {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}

tasks {
    test {
        useJUnitPlatform()
        // The git-backed tests shell out to `git`; fail loudly rather than
        // silently passing if it is missing from the build environment.
        doFirst {
            val hasGit = try {
                ProcessBuilder("git", "--version").start().waitFor() == 0
            } catch (e: Exception) {
                false
            }
            require(hasGit) { "git executable not found; WorktreeGit tests cannot run" }
        }
    }
}
