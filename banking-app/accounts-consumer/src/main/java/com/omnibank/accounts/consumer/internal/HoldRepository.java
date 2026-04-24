package com.omnibank.accounts.consumer.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<HoldEntity, UUID> {

    List<HoldEntity> findByAccountNumberAndReleasedAtIsNull(String accountNumber);
}
