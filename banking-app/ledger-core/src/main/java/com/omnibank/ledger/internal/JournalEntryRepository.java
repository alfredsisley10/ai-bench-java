package com.omnibank.ledger.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntryEntity, Long> {

    Optional<JournalEntryEntity> findByBusinessKey(String businessKey);

    @Query("""
        select j from JournalEntryEntity j
        join j.lines l
        where l.glAccount = :account
          and j.postingDate between :from and :to
        order by j.sequence
    """)
    List<JournalEntryEntity> findJournalsForAccount(
            @Param("account") String account,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
