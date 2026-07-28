package com.example.worktree.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

data class WorktreeInfo(
    val path: String,
    val sha: String,
    val branch: String,
    val name: String
)

data class DiffStats(
    val filesChanged: Int,
    val insertions: Int,
    val deletions: Int
)

/**
 * Branch sync state of a worktree. [upstream] is the tracked remote branch (or
 * null when the branch has no upstream). [ahead]/[behind] are commit counts vs
 * that upstream; [baseAhead] is how many commits this branch adds on top of its
 * merge-base with [base] (the shared trunk, e.g. main). [dirty] is true when the
 * worktree has uncommitted or untracked changes. Any count is -1 when it could
 * not be computed (e.g. no upstream, or [base] does not exist).
 */
data class BranchStatus(
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    val base: String?,
    val baseAhead: Int,
    val dirty: Boolean
)

/** One commit in a worktree's log. [parents] are the parent SHAs (2+ = merge). */
data class CommitInfo(
    val sha: String,
    val shortSha: String,
    val subject: String,
    val author: String,
    val relativeDate: String,
    val parents: List<String> = emptyList()
)

/**
 * A commit plus its computed position in the drawn graph.
 * [column] is the lane index (0-based) the commit's node sits on.
 * [nodeColor] is a stable color index for that lane.
 * [edges] are the connector segments to draw in THIS row: each goes from a top
 * lane to a bottom lane (the row is one commit tall), so vertical pass-throughs,
 * branch splits, and merges are all expressed as top→bottom pairs.
 * [laneCount] is how many lanes are occupied in this row (for width sizing).
 */
data class GraphCommit(
    val commit: CommitInfo,
    val column: Int,
    val nodeColor: Int,
    val edges: List<GraphEdge>,
    val laneCount: Int
)

/** A connector line in one graph row, from [fromColumn] (top) to [toColumn] (bottom). */
data class GraphEdge(
    val fromColumn: Int,
    val toColumn: Int,
    val color: Int
)

@Service(Service.Level.PROJECT)
class WorktreeService(private val project: Project) {

    private val logger = thisLogger()

    fun getWorktrees(): List<WorktreeInfo> {
        val rootPath = project.basePath ?: return emptyList()
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
                                result.add(createWorktreeObj(currentPath, currentSha, currentBranch))
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
                    result.add(createWorktreeObj(currentPath, currentSha, currentBranch))
                }
            }

            process.waitFor()
        } catch (e: Exception) {
            logger.warn("Failed to list git worktrees", e)
        }

        return result
    }

    private fun createWorktreeObj(path: String, sha: String, branch: String): WorktreeInfo {
        val name = File(path).name
        return WorktreeInfo(path, sha, branch, name)
    }

    fun getBranchName(worktreePath: String): String =
        runCommand(worktreePath, listOf("git", "branch", "--show-current"))

    /** The full HEAD commit SHA of the worktree, or "" if it cannot be read. */
    fun getHeadSha(worktreePath: String): String =
        runCommand(worktreePath, listOf("git", "rev-parse", "HEAD")).trim()

    /**
     * Returns the HEAD (committed) version of a file in the worktree, or null if
     * the file does not exist at HEAD (i.e. it was newly added in the worktree).
     */
    fun getFileAtHead(worktreePath: String, relativePath: String): String? =
        showFile(worktreePath, "HEAD", relativePath)

    /**
     * A file's contents at revision [rev] (e.g. "HEAD", a commit SHA, or "<sha>^").
     * Returns null when the file does not exist at that revision, git errors, or
     * the command times out. stdout carries the file content and MUST NOT be
     * polluted by stderr, so stderr is drained on a separate daemon thread (the
     * same deadlock-avoidance pattern as runCommand) rather than redirected — a
     * large file on stdout could otherwise deadlock against stderr's pipe buffer.
     */
    private fun showFile(worktreePath: String, rev: String, relativePath: String): String? {
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
                logger.warn("git show $rev:$relativePath timed out in $worktreePath")
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
     * shared trunk (auto-detected: origin/HEAD → origin/main → origin/master →
     * main → master), and whether the tree is dirty. All git calls are read-only.
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
            // "<behind>\t<ahead>" — commits on each side of HEAD...@{upstream}.
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

    // Cached base-branch detection. Every worktree of a repository shares the
    // same trunk and this service is project-scoped, so detection (up to 6 git
    // processes) runs once instead of once per worktree on every reload. Holds
    // BASE_UNCOMPUTED until first computed; thereafter the resolved name or null.
    @Volatile
    private var cachedBaseBranch: String? = BASE_UNCOMPUTED

    /**
     * Best-effort detection of the shared trunk to diff a worktree against.
     * Returns the first candidate that resolves to a real ref, or null if none
     * do (e.g. a repo with no origin and an unusual default branch name). Cached
     * for the project after the first call (see [cachedBaseBranch]).
     */
    private fun detectBaseBranch(worktreePath: String): String? {
        cachedBaseBranch.let { if (it !== BASE_UNCOMPUTED) return it }
        // origin/HEAD points at the remote's default branch when set.
        val originHead = runCommand(
            worktreePath,
            listOf("git", "rev-parse", "--abbrev-ref", "origin/HEAD")
        ).takeIf { it.isNotBlank() && it != "origin/HEAD" }
        val candidates = listOfNotNull(originHead) +
            listOf("origin/main", "origin/master", "main", "master")
        return candidates.firstOrNull { refExists(worktreePath, it) }.also { cachedBaseBranch = it }
    }

    private fun refExists(worktreePath: String, ref: String): Boolean {
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
     * Recent commits reachable from the worktree's HEAD, newest first, capped at
     * [limit]. Fields are separated by an ASCII Unit Separator (0x1f) so subjects
     * containing tabs or other whitespace parse cleanly.
     */
    fun getCommits(worktreePath: String, limit: Int = 50): List<CommitInfo> =
        getCommits(worktreePath, limit, base = null)

    /**
     * Recent commits, newest first, always anchored at HEAD, capped at [limit].
     * When [base] resolves, the range is `HEAD --not <base> --boundary`, so the
     * list is the branch's own commits plus the boundary (merge-base) commit that
     * shows where it diverged from the trunk -- never the trunk's own history.
     *
     * A plain `git log HEAD <base>` would instead take the newest [limit] from
     * the UNION of both refs, so a base that is [limit]+ commits ahead would push
     * every one of the worktree's own commits off the list. --topo-order keeps
     * parents strictly after children, which the lane layout relies on.
     */
    fun getCommits(worktreePath: String, limit: Int, base: String?): List<CommitInfo> {
        val sep = "\u001f"
        val format = "%H$sep%h$sep%s$sep%an$sep%ar$sep%P"
        val cmd = mutableListOf("git", "log", "-n", limit.toString(), "--topo-order", "--format=$format", "HEAD")
        if (base != null) {
            // Limit to what HEAD adds over base, keeping the boundary commit so the
            // divergence point (and thus the branch/merge shape) stays visible.
            cmd.addAll(listOf("--boundary", "--not", base))
        }
        return runCommand(worktreePath, cmd)
            .split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
                // `--boundary` prefixes boundary commits with '-'; strip it so the
                // SHA parses (the marker is only needed by git's text renderer).
                val clean = line.removePrefix("-")
                val parts = clean.split(sep)
                if (parts.size >= 5) {
                    val parents = if (parts.size >= 6) parts[5].split(" ").filter { it.isNotBlank() } else emptyList()
                    CommitInfo(parts[0], parts[1], parts[2], parts[3], parts[4], parents)
                } else null
            }
    }

    /**
     * Builds a laid-out commit graph for a worktree over HEAD + [base] (when
     * [base] resolves). The layout is a standard single-pass lane algorithm:
     * each row assigns its commit to the leftmost lane reserving its SHA (or a
     * new lane), then rewrites that lane's expected SHA to the commit's first
     * parent and opens fresh lanes for any additional parents. Every open lane
     * from the previous row is carried down as an edge, so vertical lines, splits
     * and merges all render correctly. See [GraphCommit]/[GraphEdge].
     */
    fun getGraphCommits(worktreePath: String, limit: Int = 50, base: String? = null): List<GraphCommit> {
        val resolvedBase = base?.takeIf { refExists(worktreePath, it) }
        val commits = getCommits(worktreePath, limit, resolvedBase)
        if (commits.isEmpty()) return emptyList()

        // lanes[i] = the SHA currently expected to appear at lane i, or null if free.
        val lanes = ArrayList<String?>()
        // Color per lane column, keyed by column index and NEVER shrunk (unlike
        // `lanes`, which is trimmed): a column that is freed and later reopened
        // keeps its original hue, so a branch line's color stays stable down the
        // list instead of flickering when trailing lanes are trimmed.
        val laneColor = ArrayList<Int>()
        var nextColor = 0
        fun colorForColumn(col: Int): Int {
            while (laneColor.size <= col) laneColor.add(nextColor++)
            return laneColor[col]
        }
        val result = ArrayList<GraphCommit>(commits.size)

        for (commit in commits) {
            // Find (or open) the lane this commit sits on.
            var col = lanes.indexOf(commit.sha)
            if (col == -1) {
                col = lanes.indexOfFirst { it == null }
                if (col == -1) {
                    col = lanes.size
                    lanes.add(null)
                }
                lanes[col] = commit.sha
            }
            val nodeColor = colorForColumn(col)

            // Snapshot the top-of-row lane state so edges reference the incoming lanes.
            val topLanes = ArrayList(lanes)

            // The commit's node consumes its lane; route its first parent through
            // the SAME lane and give each extra parent its own (merge inputs).
            val firstParent = commit.parents.getOrNull(0)
            lanes[col] = firstParent // may be null for a root commit -> lane closes

            for (i in 1 until commit.parents.size) {
                val p = commit.parents[i]
                // Reuse a lane already waiting on this parent (merge target), else open one.
                var pcol = lanes.indexOf(p)
                if (pcol == -1) {
                    pcol = lanes.indexOfFirst { it == null }
                    if (pcol == -1) {
                        pcol = lanes.size
                        lanes.add(null)
                    }
                    lanes[pcol] = p
                    colorForColumn(pcol) // reserve/keep this column's stable hue
                }
            }

            // Any other top lane whose SHA equals this commit (a second child
            // pointing here) also collapses into this row's node.
            for (i in topLanes.indices) {
                if (i != col && topLanes[i] == commit.sha) lanes[i] = null
            }

            // Build the edges for this row from each occupied top lane to where
            // its SHA now lives on the bottom (this commit's node is the sink for
            // lanes that pointed at it).
            val edges = ArrayList<GraphEdge>()
            for (i in topLanes.indices) {
                val topSha = topLanes[i] ?: continue
                val color = colorForColumn(i)
                if (topSha == commit.sha) {
                    edges.add(GraphEdge(i, col, color)) // feeds the node
                } else {
                    val bottom = lanes.indexOf(topSha)
                    if (bottom != -1) edges.add(GraphEdge(i, bottom, color)) // pass-through
                }
            }
            // Parent lanes that leave the node going SIDEWAYS (splits / extra merge
            // inputs). A parent staying in the node's own column (bottom == col) is
            // a straight vertical already drawn by the feed edge above, so skip it
            // to avoid emitting a duplicate GraphEdge(col, col).
            for (p in commit.parents) {
                val bottom = lanes.indexOf(p)
                if (bottom != -1 && bottom != col && topLanes.getOrNull(bottom) != p) {
                    edges.add(GraphEdge(col, bottom, colorForColumn(bottom)))
                }
            }

            val laneCount = maxOf(topLanes.size, lanes.size)
            result.add(GraphCommit(commit, col, nodeColor, edges, laneCount))

            // Trim trailing free lanes so the graph does not keep widening. Keep
            // laneColor untrimmed so a reopened column keeps its stable hue.
            while (lanes.isNotEmpty() && lanes.last() == null) {
                lanes.removeAt(lanes.size - 1)
            }
        }
        return result
    }

    /**
     * Files touched by a single commit vs its first parent (`<sha>^ <sha>`), the
     * SAME basis openCommitDiff uses for content, so the list and the diffs can
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
     * A file's contents at an arbitrary revision (e.g. a commit's parent), or
     * null when the file did not exist there (added in that commit).
     */
    fun getFileAtRevision(worktreePath: String, rev: String, relativePath: String): String? =
        showFile(worktreePath, rev, relativePath)

    /**
     * Removes a worktree. Returns true on success. Without [force], git refuses
     * to remove a worktree that has uncommitted or untracked changes.
     */
    fun removeWorktree(worktreePath: String, force: Boolean = false): Boolean {
        val rootPath = project.basePath ?: return false
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
                logger.warn("git worktree remove timed out: $worktreePath")
                return false
            }
            val exitCode = process.exitValue()
            if (exitCode != 0) logger.warn("git worktree remove failed (exit $exitCode): $output")
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
                logger.warn("Command timed out after ${COMMAND_TIMEOUT_SEC}s: ${command.joinToString(" ")}")
                return ""
            }
            val exitCode = process.exitValue()
            errThread.join()
            if (exitCode != 0 && errText.isNotBlank()) {
                logger.warn("Command exited $exitCode: ${command.joinToString(" ")}\n${errText.toString().trim()}")
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

        /** Distinct-instance sentinel meaning "base branch not yet detected". */
        private val BASE_UNCOMPUTED = String(" uncomputed".toCharArray())
    }
}
