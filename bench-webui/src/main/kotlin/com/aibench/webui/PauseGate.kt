package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System-wide soft pause for both LLM dispatch and local scoring.
 * Both [AdaptiveThrottler.acquire] and [WorktreePool.acquire] consult
 * [awaitNotPaused] before taking a permit, so flipping this gate
 * stops new work from being dispatched while letting in-flight runs
 * (LLM call already issued / scoring already started) complete
 * gracefully.
 *
 * Distinct from [BenchmarkRunService.cancel]/`cancelAll`, which is
 * a HARD stop -- it marks runs CANCELED immediately. Pause is the
 * SOFT control: the operator can drain the system to a stable
 * state (let in-flight finish), inspect, then resume.
 */
@Component
class PauseGate {
    private val log = LoggerFactory.getLogger(PauseGate::class.java)
    private val paused = AtomicBoolean(false)
    private val lock = Object()

    fun isPaused(): Boolean = paused.get()

    fun pause() {
        if (paused.compareAndSet(false, true)) {
            log.info("PauseGate: PAUSED -- new dispatches will block until resume()")
        }
    }

    fun resume() {
        if (paused.compareAndSet(true, false)) {
            log.info("PauseGate: RESUMED -- draining waiters")
            synchronized(lock) { lock.notifyAll() }
        }
    }

    /**
     * Block while paused. Wakes every 5s defensively in case a
     * notify is missed (cooperative; no lost-wakeup risk in the
     * happy path since resume() always notifyAll's). Returns
     * immediately when not paused.
     */
    fun awaitNotPaused() {
        while (paused.get()) {
            synchronized(lock) {
                if (paused.get()) lock.wait(5000)
            }
        }
    }
}
