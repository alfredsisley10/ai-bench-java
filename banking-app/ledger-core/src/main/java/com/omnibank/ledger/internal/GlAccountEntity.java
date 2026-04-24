package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.AccountType;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "gl_account", schema = "ledger")
public class GlAccountEntity extends AuditableEntity {

    @Id
    @Column(name = "code", length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    protected GlAccountEntity() {}

    public GlAccountEntity(String code, AccountType type, CurrencyCode currency, String displayName) {
        this.code = code;
        this.type = type;
        this.currency = currency;
        this.displayName = displayName;
        this.closed = false;
    }

    public String code() { return code; }
    public AccountType type() { return type; }
    public CurrencyCode currency() { return currency; }
    public String displayName() { return displayName; }
    public boolean isClosed() { return closed; }

    public void close() { this.closed = true; }
}
