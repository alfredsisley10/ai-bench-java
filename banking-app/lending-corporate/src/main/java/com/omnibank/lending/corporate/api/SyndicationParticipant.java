package com.omnibank.lending.corporate.api;

import com.omnibank.shared.domain.PartyId;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One participant in a syndicated loan. The lead bank administers and each
 * participant holds a pro-rata commitment. Share is an exact fraction
 * (e.g. 0.25 = 25%).
 */
public record SyndicationParticipant(PartyId party, BigDecimal share) {

    public SyndicationParticipant {
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(share, "share");
        if (share.signum() <= 0 || share.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Share must be in (0, 1]: " + share);
        }
    }
}
