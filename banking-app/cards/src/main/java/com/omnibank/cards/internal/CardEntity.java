package com.omnibank.cards.internal;

import com.omnibank.cards.api.CardNetwork;
import com.omnibank.cards.api.CardProduct;
import com.omnibank.cards.api.CardStatus;
import com.omnibank.cards.api.CardToken;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent projection of an issued card. Immutable — mutations produce
 * a new record. Repositories keep the latest version keyed by {@link #cardId()}.
 *
 * <p>The {@link CardToken} gives the public surface and only exposes the last
 * four digits; the raw PAN lives exclusively inside the token vault.
 */
public record CardEntity(
        UUID cardId,
        CustomerId holder,
        AccountNumber linkedAccount,
        CardProduct product,
        CardNetwork network,
        CardToken token,
        CardStatus status,
        boolean virtual,
        LocalDate expirationDate,
        Money creditLimit,
        Money availableCredit,
        CurrencyCode currency,
        Instant issuedAt,
        Instant lastStatusChangeAt,
        String lastStatusChangeReason,
        int reissueCount
) {

    public CardEntity {
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(linkedAccount, "linkedAccount");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expirationDate, "expirationDate");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(lastStatusChangeAt, "lastStatusChangeAt");
        if (creditLimit != null && availableCredit != null
                && !creditLimit.currency().equals(availableCredit.currency())) {
            throw new IllegalArgumentException(
                    "creditLimit and availableCredit must be in the same currency");
        }
    }

    public CardEntity withStatus(CardStatus newStatus, Instant when, String reason) {
        return new CardEntity(
                cardId, holder, linkedAccount, product, network, token,
                newStatus, virtual, expirationDate, creditLimit, availableCredit,
                currency, issuedAt, when, reason, reissueCount);
    }

    public CardEntity withAvailableCredit(Money newAvailable) {
        return new CardEntity(
                cardId, holder, linkedAccount, product, network, token,
                status, virtual, expirationDate, creditLimit, newAvailable,
                currency, issuedAt, lastStatusChangeAt, lastStatusChangeReason, reissueCount);
    }

    public CardEntity withReissued(CardToken newToken, LocalDate newExpiration, Instant when) {
        return new CardEntity(
                cardId, holder, linkedAccount, product, network, newToken,
                CardStatus.PENDING_ACTIVATION, virtual, newExpiration,
                creditLimit, availableCredit, currency, issuedAt, when,
                "Reissued", reissueCount + 1);
    }

    public boolean isCredit() {
        return product == CardProduct.CREDIT_BASIC
                || product == CardProduct.CREDIT_REWARDS
                || product == CardProduct.CREDIT_BUSINESS;
    }

    public boolean isDebit() {
        return product == CardProduct.DEBIT_STANDARD
                || product == CardProduct.DEBIT_PREMIUM;
    }

    public boolean isPrepaid() {
        return product == CardProduct.PREPAID;
    }
}
