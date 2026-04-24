package com.omnibank.shared.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import java.time.Instant;

/**
 * Soft-delete mixin. Entities carrying this stay visible in the database but
 * are filtered out of normal queries via {@code @Where("deleted_at IS NULL")}
 * on the subclass. Hard deletes are reserved for GDPR erasure pipeline only.
 */
@MappedSuperclass
public abstract class SoftDelete extends AuditableEntity {

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 64)
    private String deletedBy;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void markDeleted(String actor, Instant when) {
        this.deletedAt = when;
        this.deletedBy = actor;
    }

    public Instant deletedAt() { return deletedAt; }
    public String deletedBy() { return deletedBy; }
}
