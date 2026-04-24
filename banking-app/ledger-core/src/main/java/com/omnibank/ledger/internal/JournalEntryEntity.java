package com.omnibank.ledger.internal;

import com.omnibank.shared.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "journal_entry", schema = "ledger")
public class JournalEntryEntity extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sequence")
    private Long sequence;

    @Column(name = "proposal_id", nullable = false, unique = true)
    private UUID proposalId;

    @Column(name = "posting_date", nullable = false)
    private LocalDate postingDate;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @Column(name = "business_key", nullable = false, unique = true, length = 128)
    private String businessKey;

    @Column(name = "description", nullable = false, length = 512)
    private String description;

    @OneToMany(mappedBy = "journal", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PostingLineEntity> lines = new ArrayList<>();

    protected JournalEntryEntity() {}

    public JournalEntryEntity(UUID proposalId, LocalDate postingDate, Instant postedAt,
                              String businessKey, String description) {
        this.proposalId = proposalId;
        this.postingDate = postingDate;
        this.postedAt = postedAt;
        this.businessKey = businessKey;
        this.description = description;
    }

    public void addLine(PostingLineEntity line) {
        lines.add(line);
        line.setJournal(this);
    }

    public Long sequence() { return sequence; }
    public UUID proposalId() { return proposalId; }
    public LocalDate postingDate() { return postingDate; }
    public Instant postedAt() { return postedAt; }
    public String businessKey() { return businessKey; }
    public String description() { return description; }
    public List<PostingLineEntity> lines() { return lines; }
}
