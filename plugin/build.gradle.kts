plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.github.comeyap"
version = "1.0.11"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2")
        bundledPlugin("Git4Idea")
    }

    // The git layer and the graph layout are free of platform types, so they run
    // as ordinary JVM tests — no IDE sandbox needed.
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        id.set("com.github.comeyap.aiworktreereviewer")
        name.set("AI Worktree Reviewer")
        version.set("1.0.11")

        ideaVersion {
            sinceBuild.set("241")
            // No upper bound: the plugin only uses stable platform + Git4Idea APIs,
            // so it stays compatible with newer IDE builds (e.g. Android Studio AI-261+).
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

    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
}
