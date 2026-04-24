package com.omnibank.ledger.internal;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GlAccountRepository extends JpaRepository<GlAccountEntity, String> {
}
