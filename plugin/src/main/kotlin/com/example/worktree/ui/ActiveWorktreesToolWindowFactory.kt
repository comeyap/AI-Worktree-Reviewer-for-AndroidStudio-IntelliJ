package com.example.worktree.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.example.worktree.service.WorktreeService
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.border.EmptyBorder

class ActiveWorktreesToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val worktreeService = project.getService(WorktreeService::class.java)
        
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(5)

        // Title and Refresh Control
        val headerPanel = JPanel(BorderLayout())
        val titleLabel = JBLabel("Active Repository Worktrees").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
        }
        headerPanel.add(titleLabel, BorderLayout.WEST)

        val refreshButton = JButton("Refresh").apply {
            addActionListener {
                // Service-controlled reload
                SwingUtilities.invokeLater {
                    // Logic to reload JBList model
                }
            }
        }
        val rightHeader = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        rightHeader.add(refreshButton)
        headerPanel.add(rightHeader, BorderLayout.EAST)
        headerPanel.border = JBUI.Borders.emptyBottom(5)
        panel.add(headerPanel, BorderLayout.NORTH)

        // Worktree list component
        val mockModel = DefaultListModel<String>().apply {
            // Retrieve listed paths outputting from git CLI
            worktreeService.getWorktrees().forEach {
                addElement(it.name + " (" + it.branch + ")")
            }
        }

        val scrollList = JBList(mockModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = SimpleListCellRenderer.create { label, value, _ ->
                label.text = value
                label.icon = com.intellij.icons.AllIcons.Nodes.Folder
            }
            addListSelectionListener { event ->
                if (!event.valueIsAdjusting) {
                    // Call diff trigger logic on worktreeService
                }
            }
        }

        panel.add(JBScrollPane(scrollList), BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
