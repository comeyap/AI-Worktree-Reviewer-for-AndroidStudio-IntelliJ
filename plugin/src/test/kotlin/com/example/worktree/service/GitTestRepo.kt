package com.example.worktree.service

import java.io.File

/**
 * A throwaway git repository for tests.
 *
 * Commits are made with an explicit identity and a fixed default branch so the
 * tests behave the same on a developer machine and in CI, regardless of the
 * user's global git config or git's default-branch setting.
 */
internal class GitTestRepo private constructor(val root: File) {

    val path: String get() = root.absolutePath

    /** Runs a git command in this repository and returns its trimmed stdout. */
    fun run(vararg args: String): String = runIn(root, *args)

    /** Writes [content] to [path] (creating parent directories) without committing. */
    fun write(path: String, content: String) {
        val f = File(root, path)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    /** Writes [path], stages everything and commits; returns the new commit SHA. */
    fun commit(message: String, path: String, content: String): String {
        write(path, content)
        run("add", "-A")
        run("commit", "-m", message)
        return head()
    }

    /** Commits with no tree change; returns the new commit SHA. */
    fun commitEmpty(message: String): String {
        run("commit", "--allow-empty", "-m", message)
        return head()
    }

    fun checkoutNew(branch: String): String = run("checkout", "-b", branch)

    fun checkout(vararg args: String): String = run("checkout", *args)

    fun head(): String = run("rev-parse", "HEAD")

    fun shortHead(): String = run("rev-parse", "--short", "HEAD")

    /** Adds a linked worktree next to this repo at [name], on a new [branch]. */
    fun addWorktree(name: String, branch: String): File {
        val dir = File(root.parentFile, name)
        run("worktree", "add", "-b", branch, dir.absolutePath)
        return dir
    }

    companion object {
        const val DEFAULT_BRANCH = "main"

        /** Creates an empty repository at [dir]. */
        fun init(dir: File): GitTestRepo {
            dir.mkdirs()
            runIn(dir, "init", "-b", DEFAULT_BRANCH)
            configure(dir)
            return GitTestRepo(dir)
        }

        /**
         * Clones [source] into [dir], giving the clone a real `origin` remote so
         * upstream tracking and remote-branch base detection can be exercised.
         */
        fun clone(source: GitTestRepo, dir: File): GitTestRepo {
            dir.parentFile?.mkdirs()
            runIn(dir.parentFile ?: dir, "clone", "--quiet", source.path, dir.absolutePath)
            configure(dir)
            return GitTestRepo(dir)
        }

        private fun configure(dir: File) {
            runIn(dir, "config", "user.email", "test@example.com")
            runIn(dir, "config", "user.name", "Test User")
            runIn(dir, "config", "commit.gpgsign", "false")
        }

        /** Runs a git command in [dir] and returns its trimmed stdout. */
        fun runIn(dir: File, vararg args: String): String {
            val process = ProcessBuilder(listOf("git") + args)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            check(exit == 0) { "git ${args.joinToString(" ")} failed ($exit) in $dir:\n$output" }
            return output.trim()
        }
    }
}
