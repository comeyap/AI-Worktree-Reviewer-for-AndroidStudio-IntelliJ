package com.example.worktree.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the lane layout. Commits are synthesised directly, so these
 * cover graph shapes that are awkward to build with real git (octopus merges,
 * lane reuse) and run without touching the filesystem.
 *
 * Convention: `commit("a", "b")` is commit "a" whose parent is "b"; the list is
 * newest first, mirroring `git log --topo-order`.
 */
class CommitGraphLayoutTest {

    private fun commit(sha: String, vararg parents: String) = CommitInfo(
        sha = sha,
        shortSha = sha.take(7),
        subject = "subject $sha",
        author = "author",
        relativeDate = "1 day ago",
        parents = parents.toList()
    )

    @Test
    fun `empty input yields empty layout`() {
        assertEquals(emptyList(), layoutGraph(emptyList()))
    }

    @Test
    fun `linear history stays in one lane`() {
        val rows = layoutGraph(listOf(commit("c", "b"), commit("b", "a"), commit("a")))

        assertEquals(listOf(0, 0, 0), rows.map { it.column })
        assertEquals(listOf(1, 1, 1), rows.map { it.laneCount })
        assertTrue(rows.all { it.nodeColor == 0 }, "one lane keeps one color")
    }

    @Test
    fun `topmost row draws no edge above its node`() {
        // The first row has nothing above it: an incoming edge would render as a
        // connector rising out of the top node into empty space.
        val rows = layoutGraph(listOf(commit("c", "b"), commit("b", "a"), commit("a")))

        assertEquals(emptyList(), rows[0].edges, "top row must not feed from above")
        assertTrue(rows[1].edges.isNotEmpty(), "later rows keep their incoming edge")
    }

    @Test
    fun `root commit closes its lane`() {
        val rows = layoutGraph(listOf(commit("b", "a"), commit("a")))

        // The root has no parent, so nothing continues below it.
        val rootRow = rows.last()
        assertTrue(
            rootRow.edges.none { it.toColumn != rootRow.column || it.fromColumn != rootRow.column },
            "root emits no outgoing lane"
        )
    }

    @Test
    fun `merge commit opens a lane for its second parent`() {
        // m merges side into main: m -> (main1, side1), both -> base
        val rows = layoutGraph(
            listOf(
                commit("m", "main1", "side1"),
                commit("main1", "base"),
                commit("side1", "base"),
                commit("base")
            )
        )

        val mergeRow = rows.first { it.commit.sha == "m" }
        assertEquals(0, mergeRow.column)
        assertEquals(2, mergeRow.laneCount, "second parent gets its own lane")
        assertTrue(
            mergeRow.edges.any { it.fromColumn == 0 && it.toColumn == 1 },
            "an edge branches sideways to the second parent's lane"
        )

        val sideRow = rows.first { it.commit.sha == "side1" }
        assertEquals(1, sideRow.column, "side branch sits on the opened lane")
        assertTrue(sideRow.nodeColor != mergeRow.nodeColor, "distinct lane gets a distinct color")
    }

    @Test
    fun `octopus merge opens one lane per extra parent`() {
        val rows = layoutGraph(
            listOf(
                commit("o", "p1", "p2", "p3"),
                commit("p1", "base"),
                commit("p2", "base"),
                commit("p3", "base"),
                commit("base")
            )
        )

        val octopus = rows.first()
        assertEquals(3, octopus.laneCount, "three parents occupy three lanes")
        assertEquals(
            setOf(1, 2),
            octopus.edges.filter { it.fromColumn == 0 }.map { it.toColumn }.toSet(),
            "both extra parents get a sideways edge"
        )
    }

    @Test
    fun `converging children collapse into a single node`() {
        // Two children of `base` are listed before it; `base` must appear once and
        // absorb both lanes rather than leaving a lane dangling.
        val rows = layoutGraph(
            listOf(commit("childA", "base"), commit("childB", "base"), commit("base"))
        )

        assertEquals(1, rows.count { it.commit.sha == "base" })
        val baseRow = rows.last()
        assertTrue(
            baseRow.edges.count { it.toColumn == baseRow.column } >= 2,
            "both children feed the shared parent's node"
        )
    }

    @Test
    fun `lane count stays minimal above a merge and never exceeds the branch count`() {
        val rows = layoutGraph(
            listOf(
                commit("head", "m"),
                commit("m", "main1", "side1"),
                commit("main1", "base"),
                commit("side1", "base"),
                commit("base")
            )
        )

        assertEquals(1, rows.first { it.commit.sha == "head" }.laneCount, "above the merge: one lane")
        assertEquals(2, rows.maxOf { it.laneCount }, "two branches never need a third lane")
        // The root is where both lanes converge, so its row is still two wide —
        // it has to draw the incoming side lane feeding into the node.
        val rootRow = rows.last()
        assertEquals(2, rootRow.laneCount)
        assertEquals(
            2,
            rootRow.edges.count { it.toColumn == rootRow.column },
            "both lanes feed the shared root"
        )
    }

    @Test
    fun `reopened column keeps its original color`() {
        // side1's lane (column 1) closes at `base`, then a second branch reopens
        // column 1 further down. The hue must not be renumbered.
        val rows = layoutGraph(
            listOf(
                commit("m1", "a1", "b1"),
                commit("a1", "base"),
                commit("b1", "base"),
                commit("base", "m2"),
                commit("m2", "a2", "b2"),
                commit("a2", "root"),
                commit("b2", "root"),
                commit("root")
            )
        )

        val firstSecondLane = rows.first { it.commit.sha == "b1" }
        val reopenedSecondLane = rows.first { it.commit.sha == "b2" }
        assertEquals(1, firstSecondLane.column)
        assertEquals(1, reopenedSecondLane.column)
        assertEquals(
            firstSecondLane.nodeColor,
            reopenedSecondLane.nodeColor,
            "column 1 keeps one hue even after being trimmed and reopened"
        )
    }

    @Test
    fun `no row emits a self edge on its own column`() {
        // GraphEdge(col, col) would be drawn twice by the renderer.
        val rows = layoutGraph(
            listOf(
                commit("m", "main1", "side1"),
                commit("main1", "base"),
                commit("side1", "base"),
                commit("base")
            )
        )

        rows.forEach { row ->
            val duplicates = row.edges.filter { it.fromColumn == row.column && it.toColumn == row.column }
            assertTrue(duplicates.size <= 1, "row ${row.commit.sha} duplicates its vertical edge")
        }
    }

    @Test
    fun `all commits are own when no base is supplied`() {
        val rows = layoutGraph(listOf(commit("b", "a"), commit("a")))

        assertTrue(rows.all { it.isOwn }, "without a base every commit counts as the branch's own")
    }

    @Test
    fun `an empty ownShas set means the branch added nothing on top of its base`() {
        // Distinct from null: the base IS known here, so none of this history
        // belongs to the branch. Conflating the two would mark a freshly created
        // worktree's shared history as its own work.
        val rows = layoutGraph(listOf(commit("b", "a"), commit("a")), ownShas = emptySet())

        assertTrue(rows.none { it.isOwn })
    }

    @Test
    fun `commits outside ownShas are flagged as shared history`() {
        val rows = layoutGraph(
            listOf(commit("mine2", "mine1"), commit("mine1", "shared"), commit("shared")),
            ownShas = setOf("mine1", "mine2")
        )

        assertTrue(rows[0].isOwn)
        assertTrue(rows[1].isOwn)
        assertFalse(rows[2].isOwn, "a commit the base already has is shared history")
    }

    @Test
    fun `every edge lands inside the row's lane count`() {
        // The renderer sizes the lane gutter from laneCount, so an edge pointing
        // past it would be drawn outside the reserved area.
        val rows = layoutGraph(
            listOf(
                commit("o", "p1", "p2", "p3"),
                commit("p1", "base"),
                commit("p2", "base"),
                commit("p3", "base"),
                commit("base")
            )
        )

        rows.forEach { row ->
            row.edges.forEach { edge ->
                assertTrue(
                    edge.fromColumn < row.laneCount && edge.toColumn < row.laneCount,
                    "edge ${edge.fromColumn}->${edge.toColumn} exceeds laneCount ${row.laneCount}"
                )
            }
        }
    }

    @Test
    fun `parses a full log line`() {
        val line = listOf(
            "1111111111111111111111111111111111111111",
            "1111111",
            "subject with\ttab",
            "Author Name",
            "2 days ago",
            "2222222222222222222222222222222222222222 3333333333333333333333333333333333333333"
        ).joinToString(COMMIT_FIELD_SEP)

        val parsed = parseCommitLine(line)!!

        assertEquals("1111111111111111111111111111111111111111", parsed.sha)
        assertEquals("subject with\ttab", parsed.subject, "tabs in the subject survive")
        assertEquals("Author Name", parsed.author)
        assertEquals(2, parsed.parents.size)
    }

    @Test
    fun `parses a root commit with an empty parent field`() {
        val line = listOf("sha", "short", "subject", "author", "now", "").joinToString(COMMIT_FIELD_SEP)

        assertEquals(emptyList(), parseCommitLine(line)!!.parents)
    }

    @Test
    fun `rejects blank and truncated lines`() {
        assertNull(parseCommitLine(""))
        assertNull(parseCommitLine("   "))
        assertNull(parseCommitLine(listOf("sha", "short", "subject").joinToString(COMMIT_FIELD_SEP)))
    }

    @Test
    fun `format string separates exactly six fields`() {
        // Guards against the format and the parser drifting apart.
        assertEquals(6, COMMIT_LOG_FORMAT.split(COMMIT_FIELD_SEP).size)
    }
}
