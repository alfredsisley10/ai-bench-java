package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.ConsumerProduct;
import com.omnibank.shared.domain.AccountNumber;

import java.security.SecureRandom;

/**
 * Generates OB-{segment}-{8 alphanumeric} account numbers. Segment letter
 * derived from product kind.
 */
final class ConsumerAccountNumbers {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private final SecureRandom rng = new SecureRandom();

    String generate(ConsumerProduct product) {
        char segment = switch (product.kind) {
            case CHECKING -> 'C';
            case SAVINGS -> 'R';
            case CD -> 'R'; // CDs share the R segment historically
        };
        StringBuilder sb = new StringBuilder("OB-").append(segment).append('-');
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(rng.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    AccountNumber generateValid(ConsumerProduct product) {
        return AccountNumber.of(generate(product));
    }
}
