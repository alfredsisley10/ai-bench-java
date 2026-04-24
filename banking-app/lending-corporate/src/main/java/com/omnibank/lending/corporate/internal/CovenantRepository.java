package com.omnibank.lending.corporate.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CovenantEntity}. Provides query methods
 * used by the {@link CovenantComplianceChecker} for scheduled testing and
 * notification workflows.
 */
public interface CovenantRepository extends JpaRepository<CovenantEntity, UUID> {

    List<CovenantEntity> findByLoanIdAndActiveTrue(UUID loanId);

    List<CovenantEntity> findByNextTestDateLessThanEqualAndActiveTrue(LocalDate testDate);

    List<CovenantEntity> findByNextTestDateBetweenAndActiveTrue(LocalDate from, LocalDate to);

    List<CovenantEntity> findByActiveTrue();
}
