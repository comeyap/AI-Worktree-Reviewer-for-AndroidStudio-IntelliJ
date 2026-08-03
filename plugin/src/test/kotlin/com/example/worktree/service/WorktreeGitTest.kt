package com.example.worktree.service

import java.io.File
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the git layer, run against throwaway repositories.
 *
 * These cover the cases that plain reading of the code got wrong before: merge
 * commits listing no files, a base branch crowding the commit list out, a branch
 * with no commits of its own emptying the list, and a vs-base diff picking up the
 * base tip instead of the merge-base.
 */
class WorktreeGitTest {

    @TempDir
    lateinit var tmp: File

    private val git = WorktreeGit()

    private fun repo(name: String = "repo"): GitTestRepo = GitTestRepo.init(File(tmp, name))

    /** An `origin`-backed clone whose default branch tracks upstream. */
    private fun cloneOf(upstream: GitTestRepo, name: String = "clone"): GitTestRepo =
        GitTestRepo.clone(upstream, File(tmp, name))

    // ----- worktree listing -----

    @Test
    fun `lists the main worktree and linked worktrees`() {
        val main = repo()
        main.commit("initial", "a.txt", "a\n")
        val linked = main.addWorktree("linked", "feature")

        val worktrees = git.getWorktrees(main.path)

        assertEquals(2, worktrees.size)
        val byName = worktrees.associateBy { it.name }
        assertContains(byName.keys, "repo")
        assertContains(byName.keys, "linked")
        assertEquals(GitTestRepo.DEFAULT_BRANCH, byName.getValue("repo").branch)
        assertEquals("feature", byName.getValue("linked").branch)
        // git reports the resolved path, which differs from absolutePath wherever
        // the temp dir sits behind a symlink (/var -> /private/var on macOS).
        assertEquals(linked.canonicalPath, File(byName.getValue("linked").path).canonicalPath)
        assertTrue(byName.getValue("repo").sha.isNotBlank(), "HEAD sha is captured")
    }

    @Test
    fun `returns no worktrees for a directory that is not a repository`() {
        val notARepo = File(tmp, "plain").apply { mkdirs() }

        assertEquals(emptyList(), git.getWorktrees(notARepo.absolutePath))
    }

    @Test
    fun `reads branch name and head sha`() {
        val r = repo()
        val sha = r.commit("initial", "a.txt", "a\n")
        r.checkoutNew("topic")

        assertEquals("topic", git.getBranchName(r.path))
        assertEquals(sha, git.getHeadSha(r.path))
    }

    // ----- uncommitted work -----

    @Test
    fun `diff stats count tracked edits and untracked files together`() {
        val r = repo()
        r.commit("initial", "tracked.txt", "one\ntwo\n")
        r.write("tracked.txt", "one\ntwo\nthree\n") // +1 tracked line
        r.write("new.txt", "x\ny\n")                // +2 untracked lines

        val stats = git.getDiffStats(r.path)

        assertEquals(2, stats.filesChanged, "untracked file counts as changed")
        assertEquals(3, stats.insertions, "tracked +1 and untracked +2 are summed")
        assertEquals(0, stats.deletions)
    }

    @Test
    fun `modified files include untracked files and match diff stats`() {
        val r = repo()
        r.commit("initial", "tracked.txt", "one\n")
        r.write("tracked.txt", "changed\n")
        r.write("new.txt", "new\n")

        val files = git.getModifiedFiles(r.path)

        assertEquals(setOf("tracked.txt", "new.txt"), files.toSet())
        assertEquals(
            git.getDiffStats(r.path).filesChanged,
            files.size,
            "the file list and the +/- badge must agree"
        )
    }

    @Test
    fun `ignored files are not reported as changes`() {
        val r = repo()
        r.commit("initial", ".gitignore", "ignored.txt\n")
        r.write("ignored.txt", "noise\n")

        assertEquals(emptyList(), git.getModifiedFiles(r.path))
        assertFalse(git.hasUncommittedChanges(r.path))
    }

    @Test
    fun `detects a clean versus a dirty worktree`() {
        val r = repo()
        r.commit("initial", "a.txt", "a\n")
        assertFalse(git.hasUncommittedChanges(r.path))

        r.write("a.txt", "changed\n")
        assertTrue(git.hasUncommittedChanges(r.path))
    }

    // ----- branch status -----

    @Test
    fun `branch status reports ahead and behind counts against upstream`() {
        val upstream = repo("upstream")
        upstream.commit("initial", "a.txt", "a\n")
        val clone = cloneOf(upstream)
        // Upstream moves twice, the clone commits three times: behind 2, ahead 3.
        upstream.commit("up 1", "u.txt", "1\n")
        upstream.commit("up 2", "u.txt", "2\n")
        repeat(3) { i -> clone.commit("local $i", "l$i.txt", "$i\n") }
        clone.run("fetch", "origin")

        val status = git.getBranchStatus(clone.path)

        assertEquals("origin/${GitTestRepo.DEFAULT_BRANCH}", status.upstream)
        assertEquals(3, status.ahead)
        assertEquals(2, status.behind)
        assertFalse(status.dirty)
    }

    @Test
    fun `branch status reports no upstream and unknown counts for a local branch`() {
        val r = repo()
        r.commit("initial", "a.txt", "a\n")
        r.checkoutNew("local-only")

        val status = git.getBranchStatus(r.path)

        assertNull(status.upstream)
        assertEquals(-1, status.ahead, "ahead is unknown without an upstream")
        assertEquals(-1, status.behind)
    }

    @Test
    fun `branch status counts commits added on top of the base branch`() {
        val upstream = repo("upstream")
        upstream.commit("initial", "a.txt", "a\n")
        val clone = cloneOf(upstream)
        clone.checkoutNew("feature")
        repeat(2) { i -> clone.commit("feature $i", "f$i.txt", "$i\n") }

        val status = git.getBranchStatus(clone.path)

        assertEquals("origin/${GitTestRepo.DEFAULT_BRANCH}", status.base)
        assertEquals(2, status.baseAhead)
    }

    @Test
    fun `base detection prefers an origin branch and is cached until invalidated`() {
        val upstream = repo("upstream")
        upstream.commit("initial", "a.txt", "a\n")
        val clone = cloneOf(upstream)

        assertEquals("origin/${GitTestRepo.DEFAULT_BRANCH}", git.detectBaseBranch(clone.path))

        // A second, unrelated repo would resolve differently, but the cached value
        // is reused until it is explicitly dropped.
        val local = repo("local-only")
        local.commit("initial", "a.txt", "a\n")
        local.checkoutNew("solo")
        assertEquals(
            "origin/${GitTestRepo.DEFAULT_BRANCH}",
            git.detectBaseBranch(local.path),
            "cached result is reused"
        )

        git.invalidateBaseBranchCache()
        assertEquals(
            GitTestRepo.DEFAULT_BRANCH,
            git.detectBaseBranch(local.path),
            "after invalidation the local trunk is detected"
        )
    }

    @Test
    fun `base detection returns null when no candidate branch exists`() {
        val r = repo()
        r.commit("initial", "a.txt", "a\n")
        r.run("branch", "-m", GitTestRepo.DEFAULT_BRANCH, "trunk-with-odd-name")

        assertNull(WorktreeGit().detectBaseBranch(r.path))
    }

    // ----- commit history -----

    @Test
    fun `commits are returned newest first and capped at the limit`() {
        val r = repo()
        r.commit("c1", "a.txt", "1\n")
        r.commit("c2", "a.txt", "2\n")
        val third = r.commit("c3", "a.txt", "3\n")

        val commits = git.getCommits(r.path, limit = 2)

        assertEquals(2, commits.size)
        assertEquals(third, commits.first().sha)
        assertEquals("c3", commits.first().subject)
        assertEquals("Test User", commits.first().author)
    }

    @Test
    fun `commit list keeps the worktree's own commits when the base is far ahead`() {
        // Regression: taking the newest N of HEAD + base pushed every one of the
        // branch's commits off the list once base was N+ commits ahead.
        val upstream = repo("upstream")
        upstream.commit("initial", "a.txt", "a\n")
        val clone = cloneOf(upstream)
        clone.checkoutNew("feature")
        val featureHead = clone.commit("my work", "f.txt", "f\n")
        repeat(5) { i -> upstream.commit("upstream $i", "u.txt", "$i\n") }
        clone.run("fetch", "origin")

        val commits = git.getCommits(clone.path, limit = 3)

        assertEquals(featureHead, commits.first().sha, "HEAD stays at the top of the list")
        assertTrue(
            commits.none { it.subject.startsWith("upstream ") },
            "the trunk's own commits do not take over the list"
        )
    }

    @Test
    fun `graph is not empty when the branch has no commits of its own`() {
        // Regression: excluding everything reachable from base emptied the list for
        // a freshly created worktree, which is the most common starting state.
        val upstream = repo("upstream")
        upstream.commit("initial", "a.txt", "a\n")
        upstream.commit("second", "b.txt", "b\n")
        val clone = cloneOf(upstream)
        clone.checkoutNew("fresh") // branched, nothing committed yet

        val graph = git.getGraphCommits(clone.path, base = "origin/${GitTestRepo.DEFAULT_BRANCH}")

        assertEquals(2, graph.size, "the shared history is still listed")
        assertTrue(graph.none { it.isOwn }, "none of it belongs to this branch yet")
    }

    @Test
    fun `graph marks the branch's own commits and dims shared history`() {
        val upstream = repo("upstream")
        upstream.commit("initial", "a.txt", "a\n")
        val clone = cloneOf(upstream)
        clone.checkoutNew("feature")
        val mine = clone.commit("mine", "f.txt", "f\n")

        val graph = git.getGraphCommits(clone.path, base = "origin/${GitTestRepo.DEFAULT_BRANCH}")

        val own = graph.filter { it.isOwn }.map { it.commit.sha }
        assertEquals(listOf(mine), own, "only the commit added on top of the trunk is own")
        assertTrue(graph.any { !it.isOwn }, "the trunk commit is present but marked shared")
    }

    @Test
    fun `graph treats every commit as own when the base does not resolve`() {
        val r = repo()
        r.commit("initial", "a.txt", "a\n")

        val graph = git.getGraphCommits(r.path, base = "origin/does-not-exist")

        assertTrue(graph.isNotEmpty())
        assertTrue(graph.all { it.isOwn })
    }

    @Test
    fun `own commit shas cover exactly what the branch adds`() {
        val upstream = repo("upstream")
        upstream.commit("initial", "a.txt", "a\n")
        val clone = cloneOf(upstream)
        clone.checkoutNew("feature")
        val first = clone.commit("f1", "f1.txt", "1\n")
        val second = clone.commit("f2", "f2.txt", "2\n")

        val own = git.getOwnCommitShas(clone.path, "origin/${GitTestRepo.DEFAULT_BRANCH}")

        assertEquals(setOf(first, second), own)
    }

    @Test
    fun `graph of an empty repository is empty`() {
        val r = repo()

        assertEquals(emptyList(), git.getGraphCommits(r.path))
    }

    // ----- per-commit files -----

    @Test
    fun `merge commit lists the files it brought in`() {
        // Regression: `git diff-tree <merge>` prints nothing, so merge nodes showed
        // an empty file list and their diff never opened.
        val r = repo()
        r.commit("initial", "a.txt", "a\n")
        r.checkoutNew("side")
        r.commit("side work", "side.txt", "s\n")
        r.checkout(GitTestRepo.DEFAULT_BRANCH)
        r.commit("main work", "main.txt", "m\n")
        r.run("merge", "--no-ff", "side", "-m", "Merge side")
        val merge = r.head()

        val files = git.getCommitFiles(r.path, merge)

        assertEquals(listOf("side.txt"), files, "the first-parent delta is listed")
    }

    @Test
    fun `root commit lists its whole tree`() {
        val r = repo()
        val root = r.commit("initial", "a.txt", "a\n")

        assertEquals(listOf("a.txt"), git.getCommitFiles(r.path, root))
    }

    @Test
    fun `commit files match the revisions used for the diff`() {
        val r = repo()
        r.commit("initial", "a.txt", "one\n")
        val second = r.commit("edit", "a.txt", "two\n")

        assertEquals(listOf("a.txt"), git.getCommitFiles(r.path, second))
        assertEquals("one\n", git.getFileAtRevision(r.path, "$second^", "a.txt"))
        assertEquals("two\n", git.getFileAtRevision(r.path, second, "a.txt"))
    }

    @Test
    fun `empty commit lists no files`() {
        val r = repo()
        r.commit("initial", "a.txt", "a\n")
        val empty = r.commitEmpty("nothing changed")

        assertEquals(emptyList(), git.getCommitFiles(r.path, empty))
    }

    // ----- vs-base comparison -----

    @Test
    fun `merge base is the fork point, not the base tip`() {
        // Regression: reading file contents at the base tip attributed a
        // teammate's later commit to this branch.
        val r = repo()
        r.commit("initial", "shared.txt", "line1\nline2\n")
        val forkPoint = r.head()
        r.checkoutNew("feature")
        r.commit("my change", "shared.txt", "line1\nline2\nMY-CHANGE\n")
        r.checkout(GitTestRepo.DEFAULT_BRANCH)
        r.commit("teammate change", "shared.txt", "TEAMMATE\nline1\nline2\n")
        r.checkout("feature")

        val mergeBase = git.getMergeBase(r.path, GitTestRepo.DEFAULT_BRANCH)

        assertEquals(forkPoint, mergeBase)
        val leftSide = git.getFileAtRevision(r.path, mergeBase!!, "shared.txt")
        assertEquals("line1\nline2\n", leftSide, "the fork-point content has no teammate change")
        assertTrue(
            git.getFileAtRevision(r.path, GitTestRepo.DEFAULT_BRANCH, "shared.txt")!!
                .contains("TEAMMATE"),
            "the base tip does contain it — which is why the merge-base is used"
        )
    }

    @Test
    fun `files since base list only what the branch touched`() {
        val r = repo()
        r.commit("initial", "a.txt", "a\n")
        r.checkoutNew("feature")
        r.commit("feature file", "feature.txt", "f\n")
        r.checkout(GitTestRepo.DEFAULT_BRANCH)
        r.commit("trunk file", "trunk.txt", "t\n")
        r.checkout("feature")

        val files = git.getFilesSinceBase(r.path, GitTestRepo.DEFAULT_BRANCH)

        assertEquals(listOf("feature.txt"), files, "the trunk's own file is not attributed here")
    }

    @Test
    fun `merge base is null for unrelated histories`() {
        val r = repo()
        r.commit("initial", "a.txt", "a\n")
        // An orphan branch shares no history with the trunk.
        r.checkout("--orphan", "unrelated")
        r.run("rm", "-rf", "--cached", ".")
        r.commit("orphan root", "b.txt", "b\n")

        assertNull(git.getMergeBase(r.path, GitTestRepo.DEFAULT_BRANCH))
    }

    // ----- file contents -----

    @Test
    fun `file missing at a revision returns null`() {
        val r = repo()
        val first = r.commit("initial", "a.txt", "a\n")
        val second = r.commit("add b", "b.txt", "b\n")

        assertNull(git.getFileAtRevision(r.path, first, "b.txt"), "b.txt did not exist yet")
        assertNotNull(git.getFileAtRevision(r.path, second, "b.txt"))
    }

    @Test
    fun `file contents are returned verbatim`() {
        val r = repo()
        val content = "first\n\tindented\nlast\n"
        val sha = r.commit("initial", "a.txt", content)

        assertEquals(content, git.getFileAtRevision(r.path, sha, "a.txt"))
    }

    @Test
    fun `ref existence is reported for real and bogus refs`() {
        val r = repo()
        r.commit("initial", "a.txt", "a\n")

        assertTrue(git.refExists(r.path, "HEAD"))
        assertTrue(git.refExists(r.path, GitTestRepo.DEFAULT_BRANCH))
        assertFalse(git.refExists(r.path, "origin/nope"))
        assertFalse(git.refExists(r.path, "HEAD^"), "the root commit has no parent")
    }

    // ----- worktree removal -----

    @Test
    fun `removes a clean worktree`() {
        val main = repo()
        main.commit("initial", "a.txt", "a\n")
        val linked = main.addWorktree("linked", "feature")

        assertTrue(git.removeWorktree(main.path, linked.absolutePath))
        assertFalse(linked.exists())
        assertEquals(1, git.getWorktrees(main.path).size)
    }

    @Test
    fun `refuses to remove a dirty worktree unless forced`() {
        val main = repo()
        main.commit("initial", "a.txt", "a\n")
        val linked = main.addWorktree("linked", "feature")
        File(linked, "uncommitted.txt").writeText("work in progress\n")

        assertFalse(git.removeWorktree(main.path, linked.absolutePath), "dirty removal is refused")
        assertTrue(linked.exists())

        assertTrue(git.removeWorktree(main.path, linked.absolutePath, force = true))
        assertFalse(linked.exists())
    }

    @Test
    fun `removing a path that is not a worktree fails without throwing`() {
        val main = repo()
        main.commit("initial", "a.txt", "a\n")

        assertFalse(git.removeWorktree(main.path, File(tmp, "nowhere").absolutePath))
    }

    // ----- failure handling -----

    @Test
    fun `queries on a non-repository return empty results instead of throwing`() {
        val plain = File(tmp, "plain").apply { mkdirs() }
        val path = plain.absolutePath

        assertEquals(emptyList(), git.getCommits(path))
        assertEquals(emptyList(), git.getModifiedFiles(path))
        assertEquals("", git.getHeadSha(path))
        assertNull(git.getFileAtRevision(path, "HEAD", "a.txt"))
        assertEquals(DiffStats(0, 0, 0), git.getDiffStats(path))
        assertFalse(git.hasUncommittedChanges(path))
    }

    @Test
    fun `failures are reported to the logger`() {
        val messages = mutableListOf<String>()
        val logging = WorktreeGit { message, _ -> messages.add(message) }
        val plain = File(tmp, "plain").apply { mkdirs() }

        logging.getCommits(plain.absolutePath)

        assertTrue(messages.isNotEmpty(), "a failing git call is logged, not swallowed")
    }
}
