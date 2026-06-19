package com.example.worktree.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.contents.FileContentImpl
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

class DiffReviewAction(
    private val project: Project,
    private val mainOriginalFile: File,
    private val worktreeModifiedFile: File,
    private val relativePath: String
) : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val originalVf: VirtualFile? = LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(mainOriginalFile)
        val modifiedVf: VirtualFile? = LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(worktreeModifiedFile)

        if (originalVf == null || modifiedVf == null) {
            // Cannot open if files do not exist or are deleted
            return
        }

        val originalContent = FileContentImpl(project, originalVf)
        val modifiedContent = FileContentImpl(project, modifiedVf)

        // Instantiates side-by-side editor with left: Main branch, right: Worktree branch
        val diffRequest = SimpleDiffRequest(
            "Review Modification - $relativePath",
            originalContent,
            modifiedContent,
            "Main (Repository Home)",
            "Worktree: " + worktreeModifiedFile.parentFile.name
        )

        DiffManager.getInstance().showDiff(project, diffRequest)
    }
}
