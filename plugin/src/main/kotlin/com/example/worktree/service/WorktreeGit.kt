package com.example.worktree.service

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Every git query the tool window needs, as plain blocking calls.
 *
 * Deliberately free of IntelliJ platform types: it takes paths and a [GitLogger]
 * instead of a Project, so the whole git layer can be exercised against a
 * throwaway repository in ordinary unit tests. [WorktreeService] is the
 * project-scoped wrapper the IDE talks to.
 *
 * All calls are read-only except [removeWorktree]. Callers must stay off the EDT.
 */
internal class WorktreeGit(private val logger: GitLogger = GitLogger { _, _ -> }) {

    /** Worktrees of the repository rooted at [rootPath], main worktree first. */
    fun getWorktrees(rootPath: String): List<WorktreeInfo> {
        val result = mutableListOf<WorktreeInfo>()
        try {
            // "git worktree list --porcelain"; stderr is merged into stdout so a
            // chatty/failing git process can never fill a separate stderr pipe and
            // deadlock. The stream is always closed via use {}.
            val process = gitProcessBuilder(listOf("git", "worktree", "list", "--porcelain"))
                .directory(File(rootPath))
                .redirectErrorStream(true)
                .start()

            var currentPath = ""
            var currentSha = ""
            var currentBranch = ""

            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    when {
                        line.startsWith("worktree ") -> {
                            if (currentPath.isNotEmpty()) {
                                result.add(toWorktreeInfo(currentPath, currentSha, currentBranch))
                            }
                            currentPath = line.substring("worktree ".length)
                            currentSha = ""
                            currentBranch = ""
                        }
                        line.startsWith("HEAD ") -> currentSha = line.substring("HEAD ".length)
                        line.startsWith("branch ") ->
                            currentBranch = line.substring("branch ".length).removePrefix("refs/heads/")
                    }
                }
                if (currentPath.isNotEmpty()) {
                    result.add(toWorktreeInfo(currentPath, currentSha, currentBranch))
                }
            }

            process.waitFor()
        } catch (e: Exception) {
            logger.warn("Failed to list git worktrees", e)
        }
        return result
    }

    private fun toWorktreeInfo(path: String, sha: String, branch: String): WorktreeInfo =
        WorktreeInfo(path, sha, branch, File(path).name)

    fun getBranchName(worktreePath: String): String =
        runCommand(worktreePath, listOf("git", "branch", "--show-current"))

    /** The full HEAD commit SHA of the worktree, or "" if it cannot be read. */
    fun getHeadSha(worktreePath: String): String =
        runCommand(worktreePath, listOf("git", "rev-parse", "HEAD")).trim()

    /**
     * Counts uncommitted changes vs HEAD, INCLUDING untracked (newly added)
     * files: tracked changes come from `git diff HEAD --numstat`, and each
     * untracked file's line count is added as insertions. `git diff` alone
     * ignores untracked files, so a worktree with only new files would
     * otherwise report "+0 -0".
     */
    fun getDiffStats(worktreePath: String): DiffStats {
        var insertions = 0
        var deletions = 0
        val changed = HashSet<String>()
        // numstat line: "<ins>\t<del>\t<path>" ("-" for binary files).
        runCommand(worktreePath, listOf("git", "diff", "HEAD", "--numstat"))
            .split("\n").filter { it.isNotBlank() }.forEach { line ->
                val parts = line.split("\t")
                if (parts.size >= 3) {
                    insertions += parts[0].toIntOrNull() ?: 0
                    deletions += parts[1].toIntOrNull() ?: 0
                    changed.add(parts[2])
                }
            }
        getUntrackedFiles(worktreePath).forEach { rel ->
            val f = File(worktreePath, rel)
            if (f.isFile) {
                insertions += countLines(f)
                changed.add(rel)
            }
        }
        return DiffStats(changed.size, insertions, deletions)
    }

    /**
     * All files with uncommitted changes vs HEAD, INCLUDING untracked (new)
     * files. Built from the SAME sources as [getDiffStats] so the file list and
     * the +/- badge can never disagree: tracked changes from
     * `git diff HEAD --name-only` plus untracked files from `git ls-files
     * --others`. (`git diff` alone omits untracked files; `git status
     * --untracked-files=all` would include them but is heavy on a large repo
     * and can return nothing while the index is locked by another git process.)
     */
    fun getModifiedFiles(worktreePath: String): List<String> {
        val tracked = runCommand(worktreePath, listOf("git", "diff", "HEAD", "--name-only"))
            .split("\n").filter { it.isNotBlank() }
        return (tracked + getUntrackedFiles(worktreePath)).distinct()
    }

    private fun getUntrackedFiles(worktreePath: String): List<String> =
        runCommand(worktreePath, listOf("git", "ls-files", "--others", "--exclude-standard"))
            .split("\n").filter { it.isNotBlank() }

    private fun countLines(file: File): Int = try {
        file.bufferedReader().use { reader ->
            var count = 0
            while (reader.readLine() != null) count++
            count
        }
    } catch (e: Exception) {
        0
    }

    /** True if the worktree has any uncommitted or untracked changes. */
    fun hasUncommittedChanges(worktreePath: String): Boolean =
        runCommand(worktreePath, listOf("git", "status", "--porcelain")).isNotBlank()

    /**
     * Computes the branch sync state of a worktree: upstream tracking name,
     * ahead/behind vs upstream, commits added on top of the merge-base with the
     * shared trunk (see [detectBaseBranch]), and whether the tree is dirty.
     */
    fun getBranchStatus(worktreePath: String): BranchStatus {
        // Upstream tracking branch, if any. rev-parse exits non-zero (empty
        // output) when the branch has no configured upstream.
        val upstream = runCommand(
            worktreePath,
            listOf("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}")
        ).takeIf { it.isNotBlank() }

        var ahead = -1
        var behind = -1
        if (upstream != null) {
            // "<ahead>\t<behind>": --left-right counts the commits reachable only
            // from the left side (HEAD) then only from the right (@{upstream}).
            val counts = runCommand(
                worktreePath,
                listOf("git", "rev-list", "--left-right", "--count", "HEAD...@{upstream}")
            ).split("\t").map { it.trim().toIntOrNull() }
            if (counts.size == 2 && counts[0] != null && counts[1] != null) {
                ahead = counts[0]!!
                behind = counts[1]!!
            }
        }

        val base = detectBaseBranch(worktreePath)
        var baseAhead = -1
        if (base != null) {
            // Commits reachable from HEAD but not from base — i.e. what this
            // branch adds on top of the shared trunk (since their merge-base).
            baseAhead = runCommand(
                worktreePath,
                listOf("git", "rev-list", "--count", "$base..HEAD")
            ).trim().toIntOrNull() ?: -1
        }

        return BranchStatus(upstream, ahead, behind, base, baseAhead, hasUncommittedChanges(worktreePath))
    }

    /** Cache state for [detectBaseBranch]; [Resolved] holds null when none matched. */
    private sealed interface BaseBranchCache {
        object Uncomputed : BaseBranchCache
        data class Resolved(val name: String?) : BaseBranchCache
    }

    // Every worktree of a repository shares the same trunk, so detection (up to
    // 6 git processes) runs once instead of once per worktree on every reload.
    @Volatile
    private var baseBranchCache: BaseBranchCache = BaseBranchCache.Uncomputed

    /** Drops the cached base branch so the next query re-detects it. */
    fun invalidateBaseBranchCache() {
        baseBranchCache = BaseBranchCache.Uncomputed
    }

    /**
     * Best-effort detection of the shared trunk to compare a worktree against:
     * the first of origin/HEAD → origin/main → origin/master → main → master that
     * resolves to a real ref, or null if none do (e.g. a repo with no origin and
     * an unusual default branch name). Cached until [invalidateBaseBranchCache].
     */
    fun detectBaseBranch(worktreePath: String): String? {
        (baseBranchCache as? BaseBranchCache.Resolved)?.let { return it.name }
        // origin/HEAD points at the remote's default branch when set.
        val originHead = runCommand(
            worktreePath,
            listOf("git", "rev-parse", "--abbrev-ref", "origin/HEAD")
        ).takeIf { it.isNotBlank() && it != "origin/HEAD" }
        val candidates = listOfNotNull(originHead) +
            listOf("origin/main", "origin/master", "main", "master")
        return candidates.firstOrNull { refExists(worktreePath, it) }
            .also { baseBranchCache = BaseBranchCache.Resolved(it) }
    }

    fun refExists(worktreePath: String, ref: String): Boolean {
        return try {
            val process = gitProcessBuilder(listOf("git", "rev-parse", "--verify", "--quiet", "$ref^{commit}"))
                .directory(File(worktreePath))
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The worktree's own history: up to [limit] commits reachable from HEAD,
     * newest first, `--topo-order` so parents come strictly after children (the
     * lane layout relies on it).
     *
     * The walk is anchored at HEAD alone. Passing the base branch as a second
     * revision would make git take the newest [limit] of the UNION, so a trunk
     * that is [limit]+ commits ahead would push every one of the worktree's
     * commits off the list; excluding the base instead (`--not <base>`) empties
     * the list for a branch that has no commits of its own yet. Divergence is
     * conveyed by [GraphCommit.isOwn] instead — see [getGraphCommits].
     */
    fun getCommits(worktreePath: String, limit: Int = DEFAULT_COMMIT_LIMIT): List<CommitInfo> =
        runCommand(
            worktreePath,
            listOf(
                "git", "log", "-n", limit.toString(), "--topo-order",
                "--format=$COMMIT_LOG_FORMAT", "HEAD"
            )
        ).split("\n").mapNotNull { parseCommitLine(it) }

    /**
     * SHAs reachable from HEAD but not from [base] — the commits this branch adds
     * on top of the trunk. Empty when [base] does not resolve.
     */
    fun getOwnCommitShas(worktreePath: String, base: String): Set<String> =
        runCommand(worktreePath, listOf("git", "rev-list", "$base..HEAD"))
            .split("\n").filter { it.isNotBlank() }.toSet()

    /**
     * The worktree's commits laid out as a lane graph. When [base] resolves, the
     * commits it already contains are flagged as shared history
     * ([GraphCommit.isOwn] = false) so the fork point is visible without
     * dropping the branch's own commits or the trunk's history from the list.
     */
    fun getGraphCommits(
        worktreePath: String,
        limit: Int = DEFAULT_COMMIT_LIMIT,
        base: String? = null
    ): List<GraphCommit> {
        val commits = getCommits(worktreePath, limit)
        if (commits.isEmpty()) return emptyList()
        // null (base unknown) and an empty set (base known, nothing added on top
        // of it) mean different things to the layout — keep them distinct.
        val ownShas = base
            ?.takeIf { refExists(worktreePath, it) }
            ?.let { getOwnCommitShas(worktreePath, it) }
        return layoutGraph(commits, ownShas)
    }

    /**
     * Files touched by a single commit vs its first parent (`<sha>^ <sha>`), the
     * SAME basis the commit diff uses for content, so the list and the diffs can
     * never disagree. `git diff-tree <merge>` prints nothing for a merge commit
     * (leaving merge nodes unclickable), whereas `git diff <sha>^ <sha>` shows the
     * first-parent delta. For a root commit `<sha>^` does not resolve, so this
     * falls back to `git show --name-only`, listing every file in that commit.
     */
    fun getCommitFiles(worktreePath: String, sha: String): List<String> {
        val files = runCommand(worktreePath, listOf("git", "diff", "--name-only", "$sha^", sha))
            .split("\n").filter { it.isNotBlank() }
        if (files.isNotEmpty() || refExists(worktreePath, "$sha^")) return files
        // Root commit (no parent): list its whole tree.
        return runCommand(worktreePath, listOf("git", "show", "--name-only", "--format=", sha))
            .split("\n").filter { it.isNotBlank() }
    }

    /** Files changed on this branch since its merge-base with [base]. */
    fun getFilesSinceBase(worktreePath: String, base: String): List<String> =
        runCommand(worktreePath, listOf("git", "diff", "--name-only", "$base...HEAD"))
            .split("\n").filter { it.isNotBlank() }

    /**
     * The merge-base (common ancestor) SHA of [base] and HEAD, or null if none.
     * This is the "diverged from" point — the correct left side for a vs-base
     * diff, so unrelated commits landed on [base]'s tip after the fork are not
     * shown as this branch's changes.
     */
    fun getMergeBase(worktreePath: String, base: String): String? =
        runCommand(worktreePath, listOf("git", "merge-base", base, "HEAD"))
            .trim().takeIf { it.isNotBlank() }

    /**
     * A file's contents at revision [rev] (e.g. "HEAD", a commit SHA, or "<sha>^").
     * Returns null when the file does not exist at that revision, git errors, or
     * the command times out. stdout carries the file content and MUST NOT be
     * polluted by stderr, so stderr is drained on a separate daemon thread (the
     * same deadlock-avoidance pattern as runCommand) rather than redirected — a
     * large file on stdout could otherwise deadlock against stderr's pipe buffer.
     */
    fun getFileAtRevision(worktreePath: String, rev: String, relativePath: String): String? {
        return try {
            val process = gitProcessBuilder(listOf("git", "show", "$rev:$relativePath"))
                .directory(File(worktreePath))
                .start()
            val errThread = Thread {
                process.errorStream.bufferedReader().use { it.readText() }
            }.apply { isDaemon = true; start() }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn("git show $rev:$relativePath timed out in $worktreePath", null)
                return null
            }
            errThread.join()
            if (process.exitValue() == 0) output else null
        } catch (e: Exception) {
            logger.warn("git show $rev:$relativePath failed in $worktreePath", e)
            null
        }
    }

    /**
     * Removes [worktreePath] from the repository rooted at [rootPath]. Returns
     * true on success. Without [force], git refuses to remove a worktree that has
     * uncommitted or untracked changes.
     */
    fun removeWorktree(rootPath: String, worktreePath: String, force: Boolean = false): Boolean {
        return try {
            val command = mutableListOf("git", "worktree", "remove")
            if (force) command.add("--force")
            command.add(worktreePath)
            val process = gitProcessBuilder(command)
                .directory(File(rootPath))
                .redirectErrorStream(true)
                .start()
            // Drain the output so the process never blocks on a full pipe.
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn("git worktree remove timed out: $worktreePath", null)
                return false
            }
            val exitCode = process.exitValue()
            if (exitCode != 0) logger.warn("git worktree remove failed (exit $exitCode): $output", null)
            exitCode == 0
        } catch (e: Exception) {
            logger.warn("Failed to remove worktree: $worktreePath", e)
            false
        }
    }

    /**
     * Runs a git command and returns its trimmed standard output.
     *
     * stdout and stderr are kept on separate streams (stderr drained on a worker
     * thread to avoid a pipe-buffer deadlock), so a chatty or failing git process
     * — e.g. a missing git-lfs filter printing "git-lfs: command not found" /
     * "fatal: the remote end hung up unexpectedly" — never leaks its diagnostics
     * into the parsed output, where they would otherwise appear as bogus
     * "changed files".
     */
    private fun runCommand(workingDir: String, command: List<String>): String {
        return try {
            val process = gitProcessBuilder(command)
                .directory(File(workingDir))
                .start()
            val errText = StringBuilder()
            val errThread = Thread {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { errText.appendLine(it) }
                }
            }.apply { isDaemon = true; start() }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn("Command timed out after ${COMMAND_TIMEOUT_SEC}s: ${command.joinToString(" ")}", null)
                return ""
            }
            val exitCode = process.exitValue()
            errThread.join()
            if (exitCode != 0 && errText.isNotBlank()) {
                logger.warn(
                    "Command exited $exitCode: ${command.joinToString(" ")}\n${errText.toString().trim()}",
                    null
                )
            }
            output.trim()
        } catch (e: Exception) {
            logger.warn("Command failed: ${command.joinToString(" ")}", e)
            ""
        }
    }

    /**
     * A ProcessBuilder for a git command with the environment hardened so git can
     * never block on interactive input: GIT_TERMINAL_PROMPT=0 disables credential
     * prompts, GIT_PAGER/PAGER=cat disables the pager, and GIT_OPTIONAL_LOCKS=0
     * avoids taking optional index locks that can stall behind another git
     * process. Callers still set the working directory and stream handling.
     */
    private fun gitProcessBuilder(command: List<String>): ProcessBuilder =
        ProcessBuilder(command).apply {
            environment().apply {
                put("GIT_TERMINAL_PROMPT", "0")
                put("GIT_PAGER", "cat")
                put("PAGER", "cat")
                put("GIT_OPTIONAL_LOCKS", "0")
            }
        }

    companion object {
        /** Upper bound for any single git invocation before it is force-killed. */
        private const val COMMAND_TIMEOUT_SEC = 30L

        /** How many commits the graph loads per worktree by default. */
        const val DEFAULT_COMMIT_LIMIT = 50
    }
}
