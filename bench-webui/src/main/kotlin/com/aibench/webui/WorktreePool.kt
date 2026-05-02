package com.aibench.webui

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded pool of pre-populated banking-app worktree dirs. Replaces
 * the "deep-copy banking-app per scoreSeed" pattern, which was OOMing
 * the JVM and saturating disk I/O when the matrix launched 144 worker
 * threads simultaneously (each walking ~tens of thousands of files).
 *
 * Design notes:
 *   - The pool's permit count IS the local-execution concurrency cap.
 *     Acquiring a worktree is the throttle; no separate semaphore.
 *   - Worktrees are NOT `git worktree add` outputs. The live banking-
 *     app working tree carries a JDK25/gradle 9.4.1 upgrade that the
 *     git `main` branch doesn't have, so a git-checkout-based worktree
 *     would land on a non-buildable codebase. Each pool dir is a plain
 *     filesystem copy of the live working tree (paid once at init).
 *   - Between acquires, the pool re-overlays the bug's `filesTouched`
 *     paths from the live banking-app — this undoes both the previous
 *     run's break-branch overlay AND the previous run's LLM patch.
 *     New files added by an LLM patch (rare) accumulate as grime;
 *     the dashboard's "Rebuild pool" admin action wipes everything.
 *   - Each worktree's `build/` and `.gradle/` survive across acquires
 *     by design — the warm gradle cache cuts per-seed test time from
 *     ~30s (cold) to ~3s (warm).
 *
 * Cap defaults to 2; override via `LOCAL_EXEC_CAP` env var. 2 is a
 * conservative safe-from-disk-thrash starting point on a typical dev
 * laptop; bump to 4 on a workstation with NVMe + many cores.
 */
@Component
class WorktreePool(
    private val bankingApp: BankingAppManager,
    @Value("\${LOCAL_EXEC_CAP:2}") private val cap: Int
) {
    private val log = LoggerFactory.getLogger(WorktreePool::class.java)

    /** Worktree pool root on local disk. Co-located with other ai-bench
     *  state so the operator's `~/.ai-bench` is the single thing to
     *  back up / wipe. */
    private val poolRoot: File = run {
        val base = System.getenv("WEBUI_DATA_DIR")
            ?: "${System.getProperty("user.home")}/.ai-bench"
        File(base, "worktrees")
    }

    /** Available worktrees; takeFirst blocks until one is released. */
    private val available = LinkedBlockingDeque<File>(cap)
    /** Total worktrees registered in the pool (== `cap` after init). */
    private val total = AtomicInteger(0)
    private val initialized = AtomicBoolean(false)

    private val skipDirs = setOf(".git", ".gradle", "build", "tmp", "out", "node_modules")

    /**
     * Lazily populate the pool on first acquire. Spreading this across
     * the first `cap` acquires would mean the first matrix's first
     * scoring run pays an extra-long delay; doing it eagerly inside
     * the first acquire is simpler and the operator sees the pause
     * just once.
     */
    private fun ensureInitialized() {
        if (initialized.get()) return
        synchronized(this) {
            if (initialized.get()) return
            poolRoot.mkdirs()
            val live = bankingApp.bankingAppDir
            require(live.isDirectory) {
                "banking-app dir not found at ${live.absolutePath} -- pool cannot initialize"
            }
            log.info("WorktreePool: initializing {} worktree(s) at {} (one-time cost)", cap, poolRoot)
            for (i in 0 until cap) {
                val dir = File(poolRoot, "wt-$i")
                if (!dir.isDirectory || dir.listFiles().isNullOrEmpty()) {
                    log.info("  populating wt-$i ...")
                    dir.mkdirs()
                    deepCopy(live, dir)
                } else {
                    log.info("  reusing existing wt-$i (already populated)")
                }
                available.put(dir)
                total.incrementAndGet()
            }
            initialized.set(true)
            log.info("WorktreePool: {} worktree(s) ready", cap)
        }
    }

    /**
     * Acquire a worktree, blocking up to `timeoutSec` seconds. The
     * returned [Lease.dir] is at the live banking-app on-disk shape
     * with the bug's break-branch versions of `filesTouched` overlaid
     * (so callers can git-apply directly without re-overlaying).
     */
    fun acquire(bug: BugCatalog.BugMetadata, timeoutSec: Long = 600): Lease {
        ensureInitialized()
        val dir = available.pollFirst(timeoutSec, TimeUnit.SECONDS)
            ?: throw IllegalStateException("WorktreePool acquire timed out after ${timeoutSec}s -- " +
                "every worktree busy. Cap=$cap; consider raising LOCAL_EXEC_CAP if disk + RAM headroom allows.")
        try {
            // Re-overlay touched files from the LIVE banking-app first.
            // This undoes whatever the previous run mutated -- both the
            // break-branch overlay and the LLM's patch. Done before the
            // break overlay below so a no-op git-show produces the
            // correct state when both refs match.
            for (path in bug.filesTouched) {
                val src = File(bankingApp.bankingAppDir, path)
                val dst = File(dir, path)
                if (src.isFile) {
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                } else if (dst.exists()) {
                    // Live tree doesn't have this path (LLM may have added
                    // it on a prior run, or path is dynamically generated).
                    // Drop it so we start clean.
                    dst.delete()
                }
            }
            // Also remove any stray .llm-patch.diff file from a prior run.
            File(dir, ".llm-patch.diff").delete()
            return Lease(dir, this)
        } catch (t: Throwable) {
            // Don't leak a worktree if reset failed.
            available.put(dir)
            throw t
        }
    }

    /** Returned to the pool by [Lease.release]. Package-private so
     *  callers always go through the lease. */
    internal fun returnToPool(dir: File) {
        available.put(dir)
    }

    fun status(): Status = Status(
        cap = cap,
        available = available.size,
        inUse = total.get() - available.size,
        rootPath = poolRoot.absolutePath
    )

    /**
     * Wipe + repopulate every worktree. Call after a run that may have
     * left grime (added files, broken gradle state). Safe to call only
     * when no worktree is checked out (no in-flight scoring).
     */
    fun rebuild() {
        synchronized(this) {
            if (available.size != total.get()) {
                throw IllegalStateException("Cannot rebuild while ${total.get() - available.size} " +
                    "worktree(s) are in use; cancel active runs first.")
            }
            available.clear()
            total.set(0)
            poolRoot.deleteRecursively()
            initialized.set(false)
            ensureInitialized()
        }
    }

    @PreDestroy
    fun shutdown() {
        // Don't delete worktrees on JVM shutdown -- keeping them across
        // bench-webui restarts means the next boot doesn't pay the
        // ~tens-of-thousands-of-files copy again. The .gradle warm
        // cache also survives, which is the bigger win.
        log.info("WorktreePool: shutdown -- {} worktree(s) preserved at {}",
            total.get(), poolRoot)
    }

    private fun deepCopy(src: File, dst: File) {
        src.walkTopDown()
            .onEnter { it == src || (it.name !in skipDirs) }
            .forEach { file ->
                val rel = file.absolutePath.removePrefix(src.absolutePath).trimStart('/')
                if (rel.isEmpty()) return@forEach
                val target = File(dst, rel)
                if (file.isDirectory) {
                    target.mkdirs()
                } else if (file.isFile) {
                    target.parentFile?.mkdirs()
                    file.copyTo(target, overwrite = true)
                    if (file.canExecute()) target.setExecutable(true)
                }
            }
    }

    data class Status(
        val cap: Int,
        val available: Int,
        val inUse: Int,
        val rootPath: String
    )

    /** Auto-released worktree handle. Use try-with-resources or
     *  finally + release() to guarantee return to the pool. */
    class Lease(val dir: File, private val pool: WorktreePool) : AutoCloseable {
        private val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) pool.returnToPool(dir)
        }
        override fun close() = release()
    }
}
