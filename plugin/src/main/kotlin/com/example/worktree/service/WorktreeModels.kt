package com.example.worktree.service

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
 * [isOwn] is true when the commit is unique to this branch (reachable from HEAD
 * but not from the base branch); false marks history already shared with the
 * trunk, which the renderer dims. It is true when no base is known.
 */
data class GraphCommit(
    val commit: CommitInfo,
    val column: Int,
    val nodeColor: Int,
    val edges: List<GraphEdge>,
    val laneCount: Int,
    val isOwn: Boolean = true
)

/** A connector line in one graph row, from [fromColumn] (top) to [toColumn] (bottom). */
data class GraphEdge(
    val fromColumn: Int,
    val toColumn: Int,
    val color: Int
)

/**
 * Sink for git diagnostics. Keeps [WorktreeGit] free of IntelliJ platform types
 * so it can be exercised in plain unit tests; the service supplies an
 * implementation backed by the platform logger.
 */
fun interface GitLogger {
    fun warn(message: String, throwable: Throwable?)
}
