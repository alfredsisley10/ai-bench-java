package com.aibench.webui.persistence

import com.aibench.webui.BenchmarkRunService
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface BenchmarkRunRepository : JpaRepository<BenchmarkRunEntity, String> {
    /** Most-recent runs first, capped via Pageable.PageRequest. */
    fun findAllByOrderByStartedAtDesc(pageable: Pageable): List<BenchmarkRunEntity>

    /** Used at startup to mark stranded RUNNING/QUEUED rows from a
     *  prior JVM as ERRORED -- the worker thread that owned them is
     *  long dead. */
    fun findAllByStatusIn(
        statuses: Collection<BenchmarkRunService.Status>
    ): List<BenchmarkRunEntity>
}
