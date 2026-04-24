package com.aibench.core

import org.eclipse.jgit.api.Git
import java.nio.file.Path

/**
 * jgit-backed worktree that clones the banking-app repo (or any git repo)
 * into a per-run directory and checks out the requested commit/branch/tag.
 */
class JGitWorktree(private val sourceRepo: Path) : Worktree {

    override fun checkout(commitRef: String, into: Path) {
        Git.cloneRepository()
            .setURI(sourceRepo.toUri().toString())
            .setDirectory(into.toFile())
            .setCloneAllBranches(true)
            .call().use { git ->
                git.checkout().setName(commitRef).call()
            }
    }

    override fun currentBranch(path: Path): String =
        Git.open(path.toFile()).use { it.repository.branch }
}
