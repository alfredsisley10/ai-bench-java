package com.omnibank.accounts.consumer.internal;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumerAccountRepository extends JpaRepository<ConsumerAccountEntity, String> {
}
