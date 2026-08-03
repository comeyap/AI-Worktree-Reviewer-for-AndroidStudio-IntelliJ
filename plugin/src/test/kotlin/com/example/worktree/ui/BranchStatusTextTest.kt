package com.example.worktree.ui

import com.example.worktree.service.BranchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests for the branch-status summary line shown under the worktree list. */
class BranchStatusTextTest {

    private fun status(
        upstream: String? = "origin/main",
        ahead: Int = 0,
        behind: Int = 0,
        base: String? = "origin/main",
        baseAhead: Int = 0,
        dirty: Boolean = false
    ) = BranchStatus(upstream, ahead, behind, base, baseAhead, dirty)

    @Test
    fun `reports being in sync with the upstream`() {
        val text = describeBranchStatus(status())

        assertTrue(text.startsWith("up to date with origin/main"), text)
        assertTrue(text.endsWith("clean"), text)
    }

    @Test
    fun `reports ahead and behind counts`() {
        val text = describeBranchStatus(status(ahead = 3, behind = 2))

        assertTrue(text.contains("vs origin/main: ↑3 ↓2"), text)
    }

    @Test
    fun `reports unknown when a count could not be computed`() {
        assertTrue(describeBranchStatus(status(ahead = -1)).contains("vs origin/main: unknown"))
        assertTrue(describeBranchStatus(status(behind = -1)).contains("vs origin/main: unknown"))
    }

    @Test
    fun `reports a missing upstream`() {
        val text = describeBranchStatus(status(upstream = null))

        assertTrue(text.startsWith("no upstream"), text)
        assertFalse(text.contains("↑"), text)
    }

    @Test
    fun `includes the commit count ahead of the base branch`() {
        val text = describeBranchStatus(status(baseAhead = 4))

        assertTrue(text.contains("4 commit(s) ahead of origin/main"), text)
    }

    @Test
    fun `omits the base segment when there is no base or no count`() {
        assertFalse(describeBranchStatus(status(base = null)).contains("ahead of"))
        assertFalse(describeBranchStatus(status(baseAhead = -1)).contains("ahead of"))
    }

    @Test
    fun `flags uncommitted changes`() {
        assertTrue(describeBranchStatus(status(dirty = true)).endsWith("uncommitted changes"))
    }

    @Test
    fun `joins every segment it has`() {
        val text = describeBranchStatus(status(ahead = 1, behind = 1, baseAhead = 1, dirty = true))

        assertEquals(3, text.split("   •   ").size, text)
    }
}
