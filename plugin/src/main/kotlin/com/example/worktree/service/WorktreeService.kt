package com.example.worktree.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * Project-scoped entry point the tool window talks to.
 *
 * All git work lives in [WorktreeGit], which knows nothing about the IntelliJ
 * platform; this class only supplies the project's root path and a logger, so the
 * git layer stays unit-testable. Every call blocks on a git process — callers must
 * stay off the EDT.
 */
@Service(Service.Level.PROJECT)
class WorktreeService(private val project: Project) {

    private val logger = thisLogger()
    private val git = WorktreeGit { message, throwable ->
        if (throwable != null) logger.warn(message, throwable) else logger.warn(message)
    }

    fun getWorktrees(): List<WorktreeInfo> =
        project.basePath?.let { git.getWorktrees(it) } ?: emptyList()

    fun getBranchName(worktreePath: String): String = git.getBranchName(worktreePath)

    fun getHeadSha(worktreePath: String): String = git.getHeadSha(worktreePath)

    fun getDiffStats(worktreePath: String): DiffStats = git.getDiffStats(worktreePath)

    fun getModifiedFiles(worktreePath: String): List<String> = git.getModifiedFiles(worktreePath)

    fun hasUncommittedChanges(worktreePath: String): Boolean = git.hasUncommittedChanges(worktreePath)

    fun getBranchStatus(worktreePath: String): BranchStatus = git.getBranchStatus(worktreePath)

    /**
     * Drops the cached base-branch detection. Called on an explicit refresh so a
     * newly added remote or a changed origin/HEAD is picked up without restarting
     * the IDE.
     */
    fun invalidateCaches() = git.invalidateBaseBranchCache()

    fun getCommits(worktreePath: String, limit: Int = WorktreeGit.DEFAULT_COMMIT_LIMIT): List<CommitInfo> =
        git.getCommits(worktreePath, limit)

    fun getGraphCommits(
        worktreePath: String,
        limit: Int = WorktreeGit.DEFAULT_COMMIT_LIMIT,
        base: String? = null
    ): List<GraphCommit> = git.getGraphCommits(worktreePath, limit, base)

    fun getCommitFiles(worktreePath: String, sha: String): List<String> =
        git.getCommitFiles(worktreePath, sha)

    fun getFilesSinceBase(worktreePath: String, base: String): List<String> =
        git.getFilesSinceBase(worktreePath, base)

    fun getMergeBase(worktreePath: String, base: String): String? =
        git.getMergeBase(worktreePath, base)

    /**
     * Returns the HEAD (committed) version of a file in the worktree, or null if
     * the file does not exist at HEAD (i.e. it was newly added in the worktree).
     */
    fun getFileAtHead(worktreePath: String, relativePath: String): String? =
        git.getFileAtRevision(worktreePath, "HEAD", relativePath)

    fun getFileAtRevision(worktreePath: String, rev: String, relativePath: String): String? =
        git.getFileAtRevision(worktreePath, rev, relativePath)

    /**
     * Removes a worktree. Returns true on success. Without [force], git refuses
     * to remove a worktree that has uncommitted or untracked changes.
     */
    fun removeWorktree(worktreePath: String, force: Boolean = false): Boolean {
        val rootPath = project.basePath ?: return false
        return git.removeWorktree(rootPath, worktreePath, force)
    }
}
