package com.example.worktree.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class ToggleWorktreesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Active Worktrees")
        if (toolWindow != null) {
            if (toolWindow.isVisible) {
                toolWindow.hide(null)
            } else {
                toolWindow.show(null)
            }
        }
    }
}
