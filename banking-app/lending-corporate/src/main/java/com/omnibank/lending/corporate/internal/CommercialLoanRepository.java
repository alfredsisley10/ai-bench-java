package com.omnibank.lending.corporate.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CommercialLoanRepository extends JpaRepository<CommercialLoanEntity, UUID> {
}
