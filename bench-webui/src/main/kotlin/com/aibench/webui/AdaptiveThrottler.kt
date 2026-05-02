package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adaptive concurrency control for LLM-bound work (Navie precomputes,
 * benchmark bridge calls, etc).
 *
 * Replaces the rigid "single mutex" approach with a permit pool that
 * grows on sustained success and shrinks on rate-limit signals — so
 * the harness doesn't have to guess upfront whether the operator's
 * quota will tolerate 1 / 2 / 4 concurrent calls. Starts conservative
 * at 1 permit; max is bounded so a runaway-success window doesn't
 * dogpile Copilot.
 *
 * Growth rule: after [GROW_AFTER_SUCCESSES] consecutive completions
 * with no rate-limit signal, +1 permit (up to [MAX_PERMITS]).
 * Shrink rule: any rate-limit signal halves the permit count (down
 * to [MIN_PERMITS]) and triggers a [COOLDOWN_SECONDS] window where
 * new acquires block — gives the upstream rate limiter a chance to
 * reset before we hit it again.
 *
 * Thread-safe; single Spring bean shared by every caller.
 */
@Component
class AdaptiveThrottler(
    private val pauseGate: PauseGate
) {
    private val log = LoggerFactory.getLogger(AdaptiveThrottler::class.java)

    private val MIN_PERMITS = 1
    private val MAX_PERMITS = 4
    private val GROW_AFTER_SUCCESSES = 3
    private val COOLDOWN_SECONDS = 60L

    /** Available permits queue; size = current cap. acquire() takes,
     *  release() puts back. Resized via grow() / shrink(). */
    private val available = LinkedBlockingDeque<Unit>(MAX_PERMITS)
    @Volatile private var currentCap: Int = MIN_PERMITS
    private val successStreak = AtomicInteger(0)

    /** Time-ordered ring of recent rate-limit signals; older entries
     *  get evicted as the window rolls. Cap kept small (operator
     *  doesn't need more than the recent few to make sense of it). */
    private val recentRateLimits = java.util.concurrent.ConcurrentLinkedDeque<Instant>()
    private val RATE_LIMIT_RING = 20

    @Volatile private var cooldownUntil: Instant? = null

    init {
        // Seed with the initial permit count.
        repeat(MIN_PERMITS) { available.add(Unit) }
    }

    /** Block until a permit is free. Honors the post-rate-limit
     *  cooldown by sleeping until cooldownUntil before taking,
     *  and honors the system-wide PauseGate -- if paused, blocks
     *  here until resumed (in-flight calls finish; new dispatches
     *  wait). */
    fun acquire() {
        pauseGate.awaitNotPaused()
        val until = cooldownUntil
        if (until != null) {
            val remain = Duration.between(Instant.now(), until)
            if (!remain.isNegative && !remain.isZero) {
                log.info("throttler: cooldown active, waiting {}ms before acquire", remain.toMillis())
                Thread.sleep(remain.toMillis())
            }
        }
        available.take()
    }

    /** Release a permit and increment the success streak. After
     *  GROW_AFTER_SUCCESSES, try to grow the pool. */
    fun release() {
        available.put(Unit)
        val streak = successStreak.incrementAndGet()
        if (streak >= GROW_AFTER_SUCCESSES) {
            successStreak.set(0)
            grow()
        }
    }

    /** Signal that a recently-completed call hit a rate limit. Halves
     *  the permit cap, opens a cooldown window. Does NOT release the
     *  permit -- caller is expected to release(...) too if it had
     *  acquired. */
    fun reportRateLimit(reason: String?) {
        recentRateLimits.addFirst(Instant.now())
        while (recentRateLimits.size > RATE_LIMIT_RING) recentRateLimits.pollLast()
        successStreak.set(0)
        val newCap = (currentCap / 2).coerceAtLeast(MIN_PERMITS)
        val drop = currentCap - newCap
        if (drop > 0) {
            // Take 'drop' permits OUT of circulation -- if none are
            // free right now we still adjust the cap so future
            // releases re-balance to the new level.
            repeat(drop) { available.pollFirst() }
            currentCap = newCap
            log.warn("throttler: rate-limit detected ({}); cap {} -> {}",
                reason?.take(120) ?: "no detail", currentCap + drop, currentCap)
        }
        cooldownUntil = Instant.now().plusSeconds(COOLDOWN_SECONDS)
    }

    private fun grow() {
        if (currentCap >= MAX_PERMITS) return
        currentCap += 1
        available.add(Unit)
        log.info("throttler: success streak met threshold, cap -> {}", currentCap)
    }

    data class Status(
        val currentCap: Int,
        val maxCap: Int,
        val minCap: Int,
        val successStreak: Int,
        val growThreshold: Int,
        val recentRateLimitCount: Int,
        val mostRecentRateLimitIso: String?,
        val cooldownActive: Boolean,
        val cooldownEndsIso: String?
    )

    fun status(): Status {
        val cd = cooldownUntil
        val active = cd != null && cd.isAfter(Instant.now())
        return Status(
            currentCap = currentCap,
            maxCap = MAX_PERMITS,
            minCap = MIN_PERMITS,
            successStreak = successStreak.get(),
            growThreshold = GROW_AFTER_SUCCESSES,
            recentRateLimitCount = recentRateLimits.size,
            mostRecentRateLimitIso = recentRateLimits.peekFirst()?.toString(),
            cooldownActive = active,
            cooldownEndsIso = cd?.toString()
        )
    }
}
