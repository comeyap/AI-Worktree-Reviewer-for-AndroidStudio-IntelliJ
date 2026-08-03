package com.example.worktree.ui

import com.example.worktree.service.BranchStatus

/**
 * One-line human summary of a branch's sync state, shown under the worktree list.
 *
 * Counts of -1 mean "could not be computed" and render as `unknown` rather than a
 * bogus number; a missing upstream and a missing base each drop their segment.
 */
internal fun describeBranchStatus(bs: BranchStatus): String {
    val parts = mutableListOf<String>()
    if (bs.upstream != null) {
        parts.add(
            when {
                bs.ahead < 0 || bs.behind < 0 -> "vs ${bs.upstream}: unknown"
                bs.ahead == 0 && bs.behind == 0 -> "up to date with ${bs.upstream}"
                else -> "vs ${bs.upstream}: ↑${bs.ahead} ↓${bs.behind}"
            }
        )
    } else {
        parts.add("no upstream")
    }
    if (bs.base != null && bs.baseAhead >= 0) {
        parts.add("${bs.baseAhead} commit(s) ahead of ${bs.base}")
    }
    parts.add(if (bs.dirty) "uncommitted changes" else "clean")
    return parts.joinToString("   •   ")
}
