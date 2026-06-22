package com.example.worktree.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.example.worktree.service.WorktreeService
import com.example.worktree.service.WorktreeInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

class ActiveWorktreesToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val worktreeService = project.getService(WorktreeService::class.java)

        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(5)

        // Title and Refresh control
        val headerPanel = JPanel(BorderLayout())
        val titleLabel = JBLabel("Active Repository Worktrees").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
        }
        headerPanel.add(titleLabel, BorderLayout.WEST)

        val refreshButton = JButton("Refresh")
        val rightHeader = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        rightHeader.add(refreshButton)
        headerPanel.add(rightHeader, BorderLayout.EAST)
        headerPanel.border = JBUI.Borders.emptyBottom(5)
        panel.add(headerPanel, BorderLayout.NORTH)

        // Worktree list — holds WorktreeInfo so selection handlers can act on it.
        val listModel = DefaultListModel<WorktreeInfo>()
        val scrollList = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = SimpleListCellRenderer.create { label, value, _ ->
                label.text = if (value != null) "${value.name} (${value.branch})" else ""
                label.icon = com.intellij.icons.AllIcons.Nodes.Folder
            }
        }
        panel.add(JBScrollPane(scrollList), BorderLayout.CENTER)

        // Load worktrees off the EDT so a slow or hanging git process never
        // freezes the IDE UI thread; results are applied back on the EDT.
        fun reload() {
            refreshButton.isEnabled = false
            ApplicationManager.getApplication().executeOnPooledThread {
                val worktrees = worktreeService.getWorktrees()
                ApplicationManager.getApplication().invokeLater {
                    listModel.clear()
                    worktrees.forEach { listModel.addElement(it) }
                    refreshButton.isEnabled = true
                }
            }
        }
        refreshButton.addActionListener { reload() }
        reload()

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
