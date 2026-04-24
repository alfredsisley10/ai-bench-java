package com.omnibank.lending.corporate.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link FacilityEntity}. Provides query methods
 * for facility lifecycle management and maturity monitoring.
 */
public interface FacilityRepository extends JpaRepository<FacilityEntity, UUID> {

    List<FacilityEntity> findByBorrowerIdAndStatusNot(UUID borrowerId, FacilityEntity.FacilityStatus status);

    List<FacilityEntity> findByCreditAgreementId(UUID creditAgreementId);

    List<FacilityEntity> findByMaturityDateBetweenAndStatusNot(
            LocalDate from, LocalDate to, FacilityEntity.FacilityStatus status);

    List<FacilityEntity> findByStatusAndMaturityDateBefore(
            FacilityEntity.FacilityStatus status, LocalDate date);
}
