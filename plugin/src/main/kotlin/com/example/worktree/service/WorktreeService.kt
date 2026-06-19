package com.example.worktree.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

data class WorktreeInfo(
    val path: String,
    val sha: String,
    val branch: String,
    val name: String
)

@Service(Service.Level.PROJECT)
class WorktreeService(private val project: Project) {

    fun getWorktrees(): List<WorktreeInfo> {
        val rootPath = project.basePath ?: return emptyList()
        val result = mutableListOf<WorktreeInfo>()
        
        try {
            // Execution of "git worktree list --porcelain"
            val process = ProcessBuilder("git", "worktree", "list", "--porcelain")
                .directory(File(rootPath))
                .start()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String? = reader.readLine()
            
            var currentPath = ""
            var currentSha = ""
            var currentBranch = ""
            
            while (line != null) {
                if (line.startsWith("worktree ")) {
                    if (currentPath.isNotEmpty()) {
                        result.add(createWorktreeObj(currentPath, currentSha, currentBranch))
                    }
                    currentPath = line.substring("worktree ".length)
                    currentSha = ""
                    currentBranch = ""
                } else if (line.startsWith("HEAD ")) {
                    currentSha = line.substring("HEAD ".length)
                } else if (line.startsWith("branch ")) {
                    currentBranch = line.substring("branch ".length).removePrefix("refs/heads/")
                }
                line = reader.readLine()
            }
            if (currentPath.isNotEmpty()) {
                result.add(createWorktreeObj(currentPath, currentSha, currentBranch))
            }
            
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return result
    }

    private fun createWorktreeObj(path: String, sha: String, branch: String): WorktreeInfo {
        val name = File(path).name
        return WorktreeInfo(path, sha, branch, name)
    }

    fun getBranchName(worktreePath: String): String {
        return runCommand(worktreePath, listOf("git", "branch", "--show-current"))
    }

    fun getDiffShortstat(worktreePath: String): String {
        return runCommand(worktreePath, listOf("git", "diff", "--shortstat"))
    }

    fun getModifiedFiles(worktreePath: String): List<String> {
        val output = runCommand(worktreePath, listOf("git", "diff", "--name-only"))
        return output.split("\n").filter { it.isNotBlank() }
    }

    fun removeWorktree(worktreePath: String) {
        val rootPath = project.basePath ?: return
        try {
            val process = ProcessBuilder("git", "worktree", "remove", worktreePath)
                .directory(File(rootPath))
                .start()
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun runCommand(workingDir: String, command: List<String>): String {
        return try {
            val process = ProcessBuilder(command)
                .directory(File(workingDir))
                .start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText().trim()
            process.waitFor()
            output
        } catch (e: Exception) {
            ""
        }
    }
}
