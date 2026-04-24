package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.PostingDirection;
import com.omnibank.shared.domain.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "posting_line", schema = "ledger")
public class PostingLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "journal_sequence", nullable = false)
    private JournalEntryEntity journal;

    @Column(name = "gl_account", nullable = false, length = 20)
    private String glAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 6)
    private PostingDirection direction;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "memo", length = 256)
    private String memo;

    protected PostingLineEntity() {}

    public PostingLineEntity(String glAccount, PostingDirection direction,
                             BigDecimal amount, CurrencyCode currency, String memo) {
        this.glAccount = glAccount;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.memo = memo;
    }

    void setJournal(JournalEntryEntity journal) {
        this.journal = journal;
    }

    public Long id() { return id; }
    public String glAccount() { return glAccount; }
    public PostingDirection direction() { return direction; }
    public BigDecimal amount() { return amount; }
    public CurrencyCode currency() { return currency; }
    public String memo() { return memo; }
}
