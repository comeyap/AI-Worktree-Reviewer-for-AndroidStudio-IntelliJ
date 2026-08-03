package com.example.worktree.service

/**
 * ASCII Unit Separator between `git log --format` fields, so subjects containing
 * tabs or other whitespace still parse cleanly.
 */
internal const val COMMIT_FIELD_SEP = "\u001F"

/** The `--format` string whose fields [parseCommitLine] expects, in order. */
internal const val COMMIT_LOG_FORMAT =
    "%H$COMMIT_FIELD_SEP%h$COMMIT_FIELD_SEP%s$COMMIT_FIELD_SEP%an$COMMIT_FIELD_SEP%ar$COMMIT_FIELD_SEP%P"

/**
 * Parses one `git log` line produced by [COMMIT_LOG_FORMAT], or returns null when
 * the line is blank or has too few fields to describe a commit. The parent field
 * may be absent (older format) or empty (root commit).
 */
internal fun parseCommitLine(line: String): CommitInfo? {
    if (line.isBlank()) return null
    val parts = line.split(COMMIT_FIELD_SEP)
    if (parts.size < 5) return null
    val parents = parts.getOrNull(5)
        ?.split(" ")
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    return CommitInfo(parts[0], parts[1], parts[2], parts[3], parts[4], parents)
}

/**
 * Lays out [commits] (newest first, parents strictly after children — i.e.
 * `--topo-order`) as a lane graph.
 *
 * Single-pass lane algorithm: each row assigns its commit to the leftmost lane
 * reserving its SHA (or opens a new lane), then rewrites that lane's expected SHA
 * to the commit's first parent and opens fresh lanes for any additional parents.
 * Every occupied lane from the top of the row is carried down as an edge, so
 * vertical pass-throughs, branch splits and merges all render.
 *
 * [ownShas] marks the commits unique to the branch; every other commit is flagged
 * as shared trunk history via [GraphCommit.isOwn]. Pass null when no base branch
 * is known, which treats every commit as the branch's own — distinct from an empty
 * set, which means a base IS known and the branch has added nothing on top of it.
 */
internal fun layoutGraph(
    commits: List<CommitInfo>,
    ownShas: Set<String>? = null
): List<GraphCommit> {
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

    for ((index, commit) in commits.withIndex()) {
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
        lanes[col] = commit.parents.getOrNull(0) // null for a root commit -> lane closes

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

        // Build the edges for this row from each occupied top lane to where its
        // SHA now lives on the bottom (this commit's node is the sink for lanes
        // that pointed at it). The first row has nothing above it, so the lane
        // it just opened emits no feed edge — otherwise the renderer would draw
        // a connector rising out of the topmost node into empty space.
        val edges = ArrayList<GraphEdge>()
        for (i in topLanes.indices) {
            val topSha = topLanes[i] ?: continue
            val color = colorForColumn(i)
            if (topSha == commit.sha) {
                if (index > 0) edges.add(GraphEdge(i, col, color)) // feeds the node
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
        val isOwn = ownShas == null || commit.sha in ownShas
        result.add(GraphCommit(commit, col, nodeColor, edges, laneCount, isOwn))

        // Trim trailing free lanes so the graph does not keep widening. Keep
        // laneColor untrimmed so a reopened column keeps its stable hue.
        while (lanes.isNotEmpty() && lanes.last() == null) {
            lanes.removeAt(lanes.size - 1)
        }
    }
    return result
}
