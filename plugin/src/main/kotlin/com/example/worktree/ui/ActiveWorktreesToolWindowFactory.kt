package com.example.worktree.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.example.worktree.service.WorktreeService
import com.example.worktree.service.WorktreeInfo
import com.example.worktree.service.DiffStats
import com.example.worktree.service.BranchStatus
import com.example.worktree.service.CommitInfo
import com.example.worktree.service.GraphCommit
import com.example.worktree.service.GraphEdge
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.impl.ProjectUtil
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.ui.scale.JBUIScale
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*

/**
 * Sentinel SHA for the synthetic "Uncommitted changes" graph node. It is not a
 * real object name (real SHAs are 40 hex chars), so it never collides with a
 * commit and reliably marks the pseudo-node in the commit list.
 */
private const val UNCOMMITTED_SHA = "__UNCOMMITTED__"

class ActiveWorktreesToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.getService(WorktreeService::class.java)
        val app = ApplicationManager.getApplication()

        // ----- Worktree list (with +/- and branch-status badges) -----
        val statsByPath = HashMap<String, DiffStats>()
        val branchStatusByPath = HashMap<String, BranchStatus>()
        val worktreeModel = DefaultListModel<WorktreeInfo>()
        val worktreeList = JBList(worktreeModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : SimpleListCellRenderer<WorktreeInfo>() {
                override fun customize(list: JList<out WorktreeInfo>, value: WorktreeInfo?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    if (value != null) {
                        val s = statsByPath[value.path]
                        val diffBadge = if (s != null) "   +${s.insertions} -${s.deletions}" else ""
                        val bs = branchStatusByPath[value.path]
                        val syncBadge = buildString {
                            if (bs != null) {
                                if (bs.ahead > 0) append("  ↑${bs.ahead}")
                                if (bs.behind > 0) append("  ↓${bs.behind}")
                                if (bs.dirty) append("  ●") // filled dot = uncommitted work
                            }
                        }
                        text = "${value.name} (${value.branch})$diffBadge$syncBadge"
                        icon = com.intellij.icons.AllIcons.Nodes.Folder
                    }
                }
            }
        }
        // Read-only summary of the selected worktree's branch state, shown under
        // the worktree list. Empty until a worktree is selected and its status
        // has been fetched off the EDT.
        val branchStatusLabel = JBLabel(" ").apply {
            border = JBUI.Borders.empty(2, 2, 4, 2)
        }

        // ----- Changed-files list -----
        // This list has two modes: it shows either the worktree's UNCOMMITTED
        // changes (default, when [shownCommit] is null) or the files touched by a
        // single COMMIT (when a commit is clicked, [shownCommit] holds it). The
        // header label and double-click behavior follow the current mode; the
        // mode resets to uncommitted whenever a worktree (re)selection happens.
        var shownCommit: CommitInfo? = null
        val filesTitleLabel = JBLabel("Changed files (double-click to diff)")
        val filesModel = DefaultListModel<String>()
        val filesList = JBList(filesModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyText.text = "Select a worktree to see its changed files"
            cellRenderer = object : SimpleListCellRenderer<String>() {
                override fun customize(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    if (value != null) {
                        text = value
                        icon = com.intellij.icons.AllIcons.FileTypes.Any_type
                    }
                }
            }
        }
        val reviewAllButton = JButton("Review all").apply { isEnabled = false }
        val reviewBaseButton = JButton("Review vs base").apply {
            isEnabled = false
            toolTipText = "Diff the working tree against the merge-base with the base branch " +
                "(main/master) — every change this branch adds, including uncommitted ones"
        }

        // ----- Commit-graph list -----
        // Each row draws the lane graph on the left (nodes + colored connectors)
        // and the commit summary on the right, so branch/merge structure over
        // HEAD + base is visible at a glance.
        val commitModel = DefaultListModel<GraphCommit>()
        val commitList = JBList(commitModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyText.text = "Select a worktree to see its commits"
            cellRenderer = CommitGraphRenderer()
        }

        // ----- Worktree-scoped actions (placed above the list) -----
        val refreshButton = JButton("Refresh")
        val openButton = JButton("Open in IDE").apply { isEnabled = false }
        val deleteButton = JButton("Delete").apply { isEnabled = false }

        // Spinner overlays shown over each list while its git data is fetched
        // off the EDT (a rotating "loading" icon, not just placeholder text).
        val worktreeLoadingPanel = JBLoadingPanel(BorderLayout(), toolWindow.disposable)
        val filesLoadingPanel = JBLoadingPanel(BorderLayout(), toolWindow.disposable)
        val commitLoadingPanel = JBLoadingPanel(BorderLayout(), toolWindow.disposable)

        // ----- Diff helpers -----
        val openDiffs = HashMap<String, VirtualFile>()
        fun showDiff(key: String, requests: List<SimpleDiffRequest>, title: String) {
            if (requests.isEmpty()) return
            val editorManager = FileEditorManager.getInstance(project)
            val cached = openDiffs[key]
            val diffFile = if (cached != null && cached.isValid && editorManager.isFileOpen(cached)) {
                cached
            } else {
                ChainDiffVirtualFile(SimpleDiffRequestChain(requests), title)
            }
            // Navigate directly to an editor to keep the diff in a tab on both old and new IDEs.
            if (OpenFileDescriptor(project, diffFile).navigateInEditor(project, true)) {
                openDiffs[key] = diffFile
            }
        }
        fun closeWorktreeDiffs(worktreePath: String) {
            val editorManager = FileEditorManager.getInstance(project)
            val prefix = "$worktreePath\n"
            openDiffs.keys.filter { it.startsWith(prefix) }.toList().forEach { key ->
                openDiffs.remove(key)?.let { if (it.isValid) editorManager.closeFile(it) }
            }
        }

        // Builds a diff request from an already-fetched HEAD text (so the slow
        // `git show` runs off the EDT in the caller). Compares the worktree's
        // committed (HEAD) version against its working-tree file, so only the
        // uncommitted changes show up.
        // Builds a request from a pre-resolved working-tree VirtualFile and the
        // already-fetched HEAD text — no VFS refresh here, so this is safe to call
        // on the EDT (the refresh is done by the caller off the EDT).
        fun buildRequest(worktree: WorktreeInfo, relativePath: String, headText: String?, workingVf: VirtualFile?): SimpleDiffRequest? {
            val factory = DiffContentFactory.getInstance()
            // A file may exist on only one side (added or deleted since HEAD);
            // use empty content for the missing side instead of skipping it.
            if (workingVf == null && headText == null) return null
            val headContent = if (headText != null) factory.create(project, headText, workingVf?.fileType) else factory.createEmpty()
            val workingContent = if (workingVf != null) factory.create(project, workingVf) else factory.createEmpty()
            return SimpleDiffRequest(
                relativePath,
                headContent,
                workingContent,
                "HEAD (${worktree.branch})",
                "Working tree"
            )
        }

        // Fetches each file's HEAD version AND resolves its working-tree
        // VirtualFile off the EDT (both the slow `git show` and the synchronous
        // VFS refresh), showing the loading spinner while the diff is prepared,
        // then opens the diff tab on the EDT.
        fun openDiffsFor(worktree: WorktreeInfo, files: List<String>, key: String, title: String) {
            if (files.isEmpty()) return
            filesLoadingPanel.startLoading()
            app.executeOnPooledThread {
                val lfs = LocalFileSystem.getInstance()
                val heads = files.associateWith { service.getFileAtHead(worktree.path, it) }
                val workingVfs = files.associateWith { lfs.refreshAndFindFileByIoFile(File(worktree.path, it)) }
                app.invokeLater {
                    val requests = files.mapNotNull { buildRequest(worktree, it, heads[it], workingVfs[it]) }
                    filesLoadingPanel.stopLoading()
                    showDiff(key, requests, title)
                }
            }
        }

        fun openSingleDiff(worktree: WorktreeInfo, relativePath: String) =
            openDiffsFor(worktree, listOf(relativePath), "${worktree.path}\n${relativePath}", relativePath)

        // Builds a diff request between two already-fetched text revisions of a
        // file (left/right). Either side may be null (file absent at that
        // revision → shown as empty). fileType is inferred from the path.
        fun buildRevRequest(
            worktree: WorktreeInfo,
            relativePath: String,
            leftText: String?,
            rightText: String?,
            leftTitle: String,
            rightTitle: String
        ): SimpleDiffRequest? {
            if (leftText == null && rightText == null) return null
            val factory = DiffContentFactory.getInstance()
            val lfs = LocalFileSystem.getInstance()
            // Use the working-tree file only to infer a file type for syntax
            // highlighting; contents always come from the passed-in revisions.
            val fileType = lfs.findFileByIoFile(File(worktree.path, relativePath))?.fileType
            val leftContent = if (leftText != null) factory.create(project, leftText, fileType) else factory.createEmpty()
            val rightContent = if (rightText != null) factory.create(project, rightText, fileType) else factory.createEmpty()
            return SimpleDiffRequest(relativePath, leftContent, rightContent, leftTitle, rightTitle)
        }

        // Opens the diff for a single commit: each changed file diffed as
        // parent (<sha>^) vs the commit (<sha>). All git shows run off the EDT.
        fun openCommitDiff(worktree: WorktreeInfo, commit: CommitInfo) {
            filesLoadingPanel.startLoading()
            app.executeOnPooledThread {
                val files = service.getCommitFiles(worktree.path, commit.sha)
                val parent = "${commit.sha}^"
                val requests = files.mapNotNull { rel ->
                    val left = service.getFileAtRevision(worktree.path, parent, rel)
                    val right = service.getFileAtRevision(worktree.path, commit.sha, rel)
                    // parent^ fails to resolve for a root commit → left stays null,
                    // which correctly renders every file as newly added.
                    buildRevRequest(worktree, rel, left, right, "Parent (${commit.shortSha}^)", "Commit ${commit.shortSha}")
                }
                app.invokeLater {
                    filesLoadingPanel.stopLoading()
                    showDiff("${worktree.path}\nCOMMIT\n${commit.sha}", requests, "${commit.shortSha} — ${commit.subject}")
                }
            }
        }

        // Diffs a single file of a commit (parent vs commit) in its own tab.
        fun openCommitFileDiff(worktree: WorktreeInfo, commit: CommitInfo, relativePath: String) {
            filesLoadingPanel.startLoading()
            app.executeOnPooledThread {
                val parent = "${commit.sha}^"
                val left = service.getFileAtRevision(worktree.path, parent, relativePath)
                val right = service.getFileAtRevision(worktree.path, commit.sha, relativePath)
                app.invokeLater {
                    val req = buildRevRequest(
                        worktree, relativePath, left, right,
                        "Parent (${commit.shortSha}^)", "Commit ${commit.shortSha}"
                    )
                    filesLoadingPanel.stopLoading()
                    if (req != null) {
                        showDiff("${worktree.path}\nCOMMIT\n${commit.sha}\n$relativePath", listOf(req), relativePath)
                    }
                }
            }
        }

        // Switches the changed-files panel into "uncommitted" mode: lists the
        // worktree's uncommitted changes (HEAD vs working tree). This is the
        // default view and also what the pseudo "Uncommitted changes" graph node
        // selects. Double-clicking a file diffs it; "Review all" pages them all.
        fun showUncommittedFiles(worktree: WorktreeInfo) {
            shownCommit = null
            filesTitleLabel.text = "Changed files — uncommitted (double-click to diff)"
            filesModel.clear()
            reviewAllButton.isEnabled = false
            filesList.emptyText.text = "Loading changed files…"
            filesLoadingPanel.startLoading()
            app.executeOnPooledThread {
                val files = service.getModifiedFiles(worktree.path)
                app.invokeLater {
                    // Drop stale results if selection moved to another worktree or
                    // into commit mode while loading.
                    if (worktreeList.selectedValue?.path != worktree.path || shownCommit != null) {
                        filesLoadingPanel.stopLoading()
                        return@invokeLater
                    }
                    filesModel.clear()
                    files.forEach { filesModel.addElement(it) }
                    reviewAllButton.isEnabled = files.isNotEmpty()
                    filesList.emptyText.text = if (files.isEmpty()) "No uncommitted changes" else "Nothing to show"
                    filesLoadingPanel.stopLoading()
                }
            }
        }

        // Switches the changed-files panel into "commit files" mode: lists the
        // files touched by [commit]. Double-clicking a file then diffs that file
        // (parent vs commit); "Review all" pages through the whole commit. The
        // panel returns to uncommitted mode when the worktree is (re)selected.
        fun showCommitFiles(worktree: WorktreeInfo, commit: CommitInfo) {
            shownCommit = commit
            filesTitleLabel.text = "Files in ${commit.shortSha} (double-click to diff)"
            filesModel.clear()
            reviewAllButton.isEnabled = false
            reviewBaseButton.isEnabled = false
            filesList.emptyText.text = "Loading commit files…"
            filesLoadingPanel.startLoading()
            app.executeOnPooledThread {
                val files = service.getCommitFiles(worktree.path, commit.sha)
                app.invokeLater {
                    // Drop stale results if selection moved on while loading.
                    if (worktreeList.selectedValue?.path != worktree.path || shownCommit?.sha != commit.sha) {
                        filesLoadingPanel.stopLoading()
                        return@invokeLater
                    }
                    filesModel.clear()
                    files.forEach { filesModel.addElement(it) }
                    reviewAllButton.isEnabled = files.isNotEmpty()
                    filesList.emptyText.text = if (files.isEmpty()) "No files in this commit" else "Nothing to show"
                    filesLoadingPanel.stopLoading()
                }
            }
        }

        // Opens a diff of everything this branch adds on top of its base branch:
        // each file diffed as base (merge-base side) vs the current working tree.
        fun reviewVsBase(worktree: WorktreeInfo, base: String) {
            filesLoadingPanel.startLoading()
            app.executeOnPooledThread {
                val files = service.getFilesSinceBase(worktree.path, base)
                // Left side is the MERGE-BASE (where this branch forked), not the
                // base tip: `base...HEAD` lists files by merge-base, so reading
                // content at the tip would show commits others landed on base
                // after the fork as this branch's changes. Fall back to the base
                // name only if no merge-base resolves.
                val mergeBase = service.getMergeBase(worktree.path, base)
                val leftRev = mergeBase ?: base
                val leftTitle = if (mergeBase != null) "merge-base ($base)" else base
                val requests = files.mapNotNull { rel ->
                    val left = service.getFileAtRevision(worktree.path, leftRev, rel)
                    val workingVf = LocalFileSystem.getInstance()
                        .refreshAndFindFileByIoFile(File(worktree.path, rel))
                    val right = workingVf?.let { String(it.contentsToByteArray(), it.charset) }
                    buildRevRequest(worktree, rel, left, right, leftTitle, "Working tree (${worktree.branch})")
                }
                app.invokeLater {
                    filesLoadingPanel.stopLoading()
                    showDiff("${worktree.path}\nBASE\n$base", requests, "${worktree.name} — vs $base")
                }
            }
        }

        // Opens every changed file in the one diff tab; page through with prev/next.
        fun reviewAll(worktree: WorktreeInfo, files: List<String>) =
            openDiffsFor(worktree, files, "${worktree.path}\nALL", "${worktree.name} — all changes")

        // Loads the commit graph for a worktree into the commit list (off the
        // EDT). The graph walks HEAD's own history; commits the base branch
        // already contains are dimmed rather than dropped, so the fork point is
        // visible without hiding the worktree's commits (see WorktreeGit).
        // When the worktree is dirty, a pseudo "Uncommitted changes" node is
        // prepended on HEAD's lane (see UNCOMMITTED_SHA); selecting it shows the
        // uncommitted files, exactly like the default worktree view.
        // [status] is passed in by the EDT caller (never read from the shared
        // branchStatusByPath on this pooled thread), so there is no cross-thread
        // HashMap access to synchronize.
        fun loadCommitsFor(worktree: WorktreeInfo, status: BranchStatus) {
            commitModel.clear()
            commitList.emptyText.text = "Loading commits…"
            commitLoadingPanel.startLoading()
            app.executeOnPooledThread {
                val commits = service.getGraphCommits(worktree.path, base = status.base)
                val headSha = service.getHeadSha(worktree.path)
                app.invokeLater {
                    // Drop stale results if the user moved to another worktree.
                    if (worktreeList.selectedValue?.path != worktree.path) {
                        commitLoadingPanel.stopLoading()
                        return@invokeLater
                    }
                    commitModel.clear()
                    // Prepend the uncommitted-changes node on HEAD's lane so it
                    // reads as "sitting on top of" the current HEAD commit. Anchor
                    // it to the row whose SHA is actually HEAD (not commits.first(),
                    // which need not be HEAD once boundary commits are included).
                    val headRow = commits.firstOrNull { it.commit.sha == headSha } ?: commits.firstOrNull()
                    if (status.dirty && headRow != null) {
                        val pseudoCommit = CommitInfo(
                            sha = UNCOMMITTED_SHA,
                            shortSha = "●",
                            subject = "Uncommitted changes",
                            author = "",
                            relativeDate = "",
                            parents = listOf(headRow.commit.sha)
                        )
                        commitModel.addElement(
                            GraphCommit(
                                commit = pseudoCommit,
                                column = headRow.column,
                                nodeColor = headRow.nodeColor,
                                // Straight line down HEAD's lane into the HEAD node.
                                edges = listOf(GraphEdge(headRow.column, headRow.column, headRow.nodeColor)),
                                laneCount = headRow.laneCount
                            )
                        )
                    }
                    commits.forEach { commitModel.addElement(it) }
                    commitList.emptyText.text = if (commits.isEmpty()) "No commits" else "Nothing to show"
                    commitLoadingPanel.stopLoading()
                    // Auto-select the uncommitted node (row 0) so the files panel
                    // shows uncommitted changes right away, preserving the prior
                    // "select a worktree → see its changes" first impression.
                    if (status.dirty && commitModel.size() > 0) {
                        commitList.selectedIndex = 0
                    } else if (status.dirty) {
                        // Dirty but no commits (e.g. brand-new repo): no pseudo-node
                        // was added to drive the panel, so populate it directly.
                        showUncommittedFiles(worktree)
                    }
                }
            }
        }

        // Reacts to a worktree selection: refreshes its +/- badge, branch-status
        // badge/label, the commit graph, and the "Review vs base" button. The
        // changed-files panel itself is driven by the commit graph — a dirty
        // worktree auto-selects the "Uncommitted changes" node (see
        // loadCommitsFor), and a clean one falls back to showUncommittedFiles.
        fun loadFilesFor(worktree: WorktreeInfo) {
            shownCommit = null
            filesTitleLabel.text = "Changed files — uncommitted (double-click to diff)"
            filesModel.clear()
            reviewAllButton.isEnabled = false
            reviewBaseButton.isEnabled = false
            branchStatusLabel.text = "Loading branch status…"
            filesList.emptyText.text = "Loading changed files…"
            filesLoadingPanel.startLoading()
            app.executeOnPooledThread {
                val stats = service.getDiffStats(worktree.path)
                val branchStatus = service.getBranchStatus(worktree.path)
                app.invokeLater {
                    statsByPath[worktree.path] = stats
                    branchStatusByPath[worktree.path] = branchStatus
                    worktreeList.repaint()
                    if (worktreeList.selectedValue?.path != worktree.path) return@invokeLater
                    branchStatusLabel.text = describeBranchStatus(branchStatus)
                    reviewBaseButton.isEnabled =
                        branchStatus.base != null && branchStatus.baseAhead > 0
                    // (Re)load the graph. When dirty, loadCommitsFor prepends and
                    // auto-selects the "Uncommitted changes" node, which drives the
                    // files panel — so we must NOT also call showUncommittedFiles
                    // here (its startLoading/stopLoading would race the node's load;
                    // JBLoadingPanel is not ref-counted). For a clean worktree no
                    // node is auto-selected, so populate the panel directly.
                    loadCommitsFor(worktree, branchStatus)
                    if (!branchStatus.dirty) showUncommittedFiles(worktree)
                }
            }
        }

        fun reloadWorktrees() {
            refreshButton.isEnabled = false
            // Spinner over the worktree list while it is (re)loaded off the EDT.
            worktreeLoadingPanel.startLoading()
            app.executeOnPooledThread {
                // An explicit refresh re-detects the base branch, so adding a
                // remote or changing origin/HEAD takes effect without a restart.
                service.invalidateCaches()
                val worktrees = service.getWorktrees()
                val stats = worktrees.associate { it.path to service.getDiffStats(it.path) }
                // Branch status feeds the ↑/↓/● badges in the worktree list, so
                // compute it here for every worktree (not just the selected one).
                val branchStatuses = worktrees.associate { it.path to service.getBranchStatus(it.path) }
                app.invokeLater {
                    statsByPath.clear()
                    statsByPath.putAll(stats)
                    branchStatusByPath.clear()
                    branchStatusByPath.putAll(branchStatuses)
                    worktreeModel.clear()
                    worktrees.forEach { worktreeModel.addElement(it) }
                    shownCommit = null
                    filesTitleLabel.text = "Changed files — uncommitted (double-click to diff)"
                    filesModel.clear()
                    commitModel.clear()
                    commitList.emptyText.text = "Select a worktree to see its commits"
                    branchStatusLabel.text = " "
                    filesList.emptyText.text = "Select a worktree to see its changed files"
                    reviewAllButton.isEnabled = false
                    reviewBaseButton.isEnabled = false
                    openButton.isEnabled = false
                    deleteButton.isEnabled = false
                    refreshButton.isEnabled = true
                    worktreeLoadingPanel.stopLoading()
                }
            }
        }

        // Removes the selected worktree after confirmation, then reloads the list.
        fun deleteWorktree(worktree: WorktreeInfo) {
            // Check for uncommitted/untracked work first, so a dirty worktree shows
            // the strong "permanent data loss" warning up front (not as a second step).
            app.executeOnPooledThread {
                val dirty = service.hasUncommittedChanges(worktree.path)
                app.invokeLater {
                    val confirmed = if (dirty) {
                        Messages.showYesNoDialog(
                            project,
                            "⚠ WARNING — THIS PERMANENTLY DELETES UNCOMMITTED WORK ⚠\n\n" +
                                "\"${worktree.name}\" has uncommitted or untracked changes.\n\n" +
                                "Path: ${worktree.path}\n\n" +
                                "Removing it will PERMANENTLY DISCARD all of those changes. " +
                                "There is NO undo and the work CANNOT be recovered.\n\n" +
                                "Only continue if you are absolutely sure you no longer need this work.",
                            "Force Remove Worktree — Permanent Data Loss",
                            "Force remove (discard all changes)",
                            "Cancel",
                            Messages.getWarningIcon()
                        ) == Messages.YES
                    } else {
                        Messages.showYesNoDialog(
                            project,
                            "Remove worktree \"${worktree.name}\"?\n${worktree.path}",
                            "Remove Worktree",
                            Messages.getQuestionIcon()
                        ) == Messages.YES
                    }
                    if (!confirmed) return@invokeLater
                    app.executeOnPooledThread {
                        val removed = service.removeWorktree(worktree.path, force = dirty)
                        app.invokeLater {
                            if (removed) {
                                closeWorktreeDiffs(worktree.path)
                            } else {
                                Messages.showErrorDialog(
                                    project,
                                    "Failed to remove worktree \"${worktree.name}\". See idea.log for details.",
                                    "Remove Worktree"
                                )
                            }
                            reloadWorktrees()
                        }
                    }
                }
            }
        }

        // Opens the worktree directory as a project in a new IDE window.
        fun openInIde(worktree: WorktreeInfo) {
            ProjectUtil.openOrImport(File(worktree.path).toPath(), project, true)
        }

        worktreeList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = worktreeList.selectedValue
                openButton.isEnabled = selected != null
                deleteButton.isEnabled = selected != null
                selected?.let { loadFilesFor(it) }
            }
        }
        // Open a file's diff on double-click only; single clicks just select.
        // Behavior follows the panel mode: uncommitted (HEAD vs working tree) or
        // commit files (parent vs commit).
        filesList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val worktree = worktreeList.selectedValue ?: return
                    val relativePath = filesList.selectedValue ?: return
                    val commit = shownCommit
                    if (commit != null) openCommitFileDiff(worktree, commit, relativePath)
                    else openSingleDiff(worktree, relativePath)
                }
            }
        })
        // Single-click a commit → show its files in the Changed files panel.
        // The synthetic "Uncommitted changes" node shows uncommitted files instead.
        // Double-click → open the whole commit (or all uncommitted) as one paged diff.
        commitList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val worktree = worktreeList.selectedValue
                val commit = commitList.selectedValue?.commit
                if (worktree != null && commit != null) {
                    if (commit.sha == UNCOMMITTED_SHA) showUncommittedFiles(worktree)
                    else showCommitFiles(worktree, commit)
                }
            }
        }
        commitList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val worktree = worktreeList.selectedValue ?: return
                    val commit = commitList.selectedValue?.commit ?: return
                    if (commit.sha == UNCOMMITTED_SHA) {
                        val files = (0 until filesModel.size()).map { i -> filesModel.getElementAt(i) }
                        reviewAll(worktree, files)
                    } else {
                        openCommitDiff(worktree, commit)
                    }
                }
            }
        })
        reviewAllButton.addActionListener {
            val worktree = worktreeList.selectedValue ?: return@addActionListener
            val commit = shownCommit
            if (commit != null) openCommitDiff(worktree, commit)
            else {
                val files = (0 until filesModel.size()).map { filesModel.getElementAt(it) }
                reviewAll(worktree, files)
            }
        }
        reviewBaseButton.addActionListener {
            val worktree = worktreeList.selectedValue ?: return@addActionListener
            val base = branchStatusByPath[worktree.path]?.base ?: return@addActionListener
            reviewVsBase(worktree, base)
        }
        refreshButton.addActionListener { reloadWorktrees() }
        deleteButton.addActionListener {
            worktreeList.selectedValue?.let { deleteWorktree(it) }
        }
        openButton.addActionListener {
            worktreeList.selectedValue?.let { openInIde(it) }
        }

        // ----- Layout -----
        // Title + worktree-scoped actions sit on top of the worktree list, so
        // it is clear that Open/Delete act on the selected worktree.
        val titleLabel = JBLabel("Active Repository Worktrees").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            border = JBUI.Borders.emptyBottom(3)
        }
        val actionToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(refreshButton)
            add(openButton)
            add(deleteButton)
        }
        val header = JPanel(BorderLayout()).apply {
            add(titleLabel, BorderLayout.NORTH)
            add(actionToolbar, BorderLayout.CENTER)
            border = JBUI.Borders.emptyBottom(5)
        }

        worktreeLoadingPanel.add(JBScrollPane(worktreeList), BorderLayout.CENTER)
        // Worktree list with the selected worktree's branch-status summary below it.
        val worktreePanel = JPanel(BorderLayout()).apply {
            add(worktreeLoadingPanel, BorderLayout.CENTER)
            add(branchStatusLabel, BorderLayout.SOUTH)
        }

        // Header label (mode-aware) with the Review buttons directly beneath it.
        val filesHeader = JPanel(BorderLayout()).apply {
            add(filesTitleLabel, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
                add(reviewAllButton)
                add(reviewBaseButton)
            }, BorderLayout.CENTER)
        }
        filesLoadingPanel.add(JBScrollPane(filesList), BorderLayout.CENTER)
        val filesPanel = JPanel(BorderLayout()).apply {
            add(filesHeader, BorderLayout.NORTH)
            add(filesLoadingPanel, BorderLayout.CENTER)
        }

        // Commit log for the selected worktree (double-click a commit to diff it).
        commitLoadingPanel.add(JBScrollPane(commitList), BorderLayout.CENTER)
        val commitPanel = JPanel(BorderLayout()).apply {
            add(JBLabel("Commits (click to list files · double-click to diff all)"), BorderLayout.NORTH)
            add(commitLoadingPanel, BorderLayout.CENTER)
        }

        // Lower half is itself split between changed files and the commit log.
        val filesAndCommits = JBSplitter(true, 0.5f).apply {
            firstComponent = filesPanel
            secondComponent = commitPanel
        }
        val splitter = JBSplitter(true, 0.4f).apply {
            firstComponent = worktreePanel
            secondComponent = filesAndCommits
        }

        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            add(header, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }

        reloadWorktrees()

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * Renders one commit row as: a lane graph on the left (colored connector lines
 * plus a filled node on the commit's lane) and the commit summary text on the
 * right. Colors cycle through a fixed palette keyed by lane color index, so each
 * branch line keeps a consistent hue down the list.
 */
private class CommitGraphRenderer : JPanel(), ListCellRenderer<GraphCommit> {
    private var row: GraphCommit? = null
    private val label = JBLabel()

    // Distinct, theme-agnostic lane colors (cycled by color index).
    private val palette = arrayOf(
        JBColor(0x3B82F6, 0x60A5FA), // blue
        JBColor(0x16A34A, 0x4ADE80), // green
        JBColor(0xDC2626, 0xF87171), // red
        JBColor(0x9333EA, 0xC084FC), // purple
        JBColor(0xEA580C, 0xFB923C), // orange
        JBColor(0x0891B2, 0x22D3EE), // cyan
        JBColor(0xCA8A04, 0xFACC15)  // amber
    )

    private val laneWidth = JBUI.scale(16)
    private val nodeRadius = JBUI.scale(4)
    private val rowHeight = JBUI.scale(22)

    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(0, 4)
        isOpaque = true
        add(label, BorderLayout.CENTER)
    }

    private fun colorFor(index: Int): JBColor = palette[index % palette.size]

    override fun getListCellRendererComponent(
        list: JList<out GraphCommit>,
        value: GraphCommit?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): java.awt.Component {
        row = value
        background = if (isSelected) list.selectionBackground else list.background
        val fg = if (isSelected) list.selectionForeground else list.foreground
        if (value != null) {
            val c = value.commit
            if (c.sha == UNCOMMITTED_SHA) {
                // Synthetic node: no author/date, shown italic + muted.
                label.text = c.subject
                label.foreground = if (isSelected) fg else JBColor.GRAY
                label.font = label.font.deriveFont(Font.ITALIC)
            } else {
                label.text = "${c.shortSha}  ${c.subject}   — ${c.author}, ${c.relativeDate}"
                // Commits the base branch already contains are dimmed, so the
                // branch's own work stands out while the shared history it grew
                // from stays visible.
                label.foreground = if (isSelected || value.isOwn) fg else JBColor.GRAY
                label.font = label.font.deriveFont(Font.PLAIN)
            }
            // Reserve space on the left for the lanes this row spans.
            val lanes = maxOf(value.laneCount, value.column + 1)
            label.border = JBUI.Borders.emptyLeft(lanes * laneWidth + JBUI.scale(4))
        } else {
            label.text = ""
            label.border = JBUI.Borders.empty()
        }
        return this
    }

    override fun getPreferredSize(): Dimension {
        val d = super.getPreferredSize()
        return Dimension(d.width, maxOf(d.height, rowHeight))
    }

    // Lane i's horizontal center within the row.
    private fun laneX(i: Int): Int = i * laneWidth + laneWidth / 2

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val r = row ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = height
            val top = 0
            val mid = h / 2
            val bottom = h
            val isUncommitted = r.commit.sha == UNCOMMITTED_SHA
            val solid = BasicStroke(JBUIScale.scale(1.6f))
            // The uncommitted node dangles above HEAD, so its connector down to
            // HEAD is dashed to read as "not yet committed"; it also has nothing
            // above it, so its top segment is skipped.
            val dashed = BasicStroke(
                JBUIScale.scale(1.6f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, floatArrayOf(JBUIScale.scale(3f), JBUIScale.scale(3f)), 0f
            )
            // Connector segments: top edge → node row → bottom edge. Splitting at
            // the vertical midpoint lets diagonal branch/merge lines bend at the node.
            for (e in r.edges) {
                g2.color = colorFor(e.color)
                g2.stroke = if (isUncommitted) dashed else solid
                val xTop = laneX(e.fromColumn)
                val xBot = laneX(e.toColumn)
                if (!isUncommitted) {
                    g2.drawLine(xTop, top, xTop, mid)
                    g2.drawLine(xTop, mid, xBot, mid)
                }
                g2.drawLine(xBot, mid, xBot, bottom)
            }
            // The commit node itself, on its own lane, drawn last so it sits atop
            // the connectors. The uncommitted node is hollow to distinguish it.
            val nx = laneX(r.column)
            val d = nodeRadius * 2
            if (isUncommitted) {
                g2.color = background
                g2.fillOval(nx - nodeRadius, mid - nodeRadius, d, d)
                g2.color = colorFor(r.nodeColor)
                g2.stroke = solid
                g2.drawOval(nx - nodeRadius, mid - nodeRadius, d, d)
            } else {
                g2.color = colorFor(r.nodeColor)
                g2.fillOval(nx - nodeRadius, mid - nodeRadius, d, d)
                // A subtle ring so the node reads clearly over same-color lines.
                g2.color = background
                g2.stroke = BasicStroke(JBUIScale.scale(1f))
                g2.drawOval(nx - nodeRadius, mid - nodeRadius, d, d)
            }
        } finally {
            g2.dispose()
        }
    }
}
