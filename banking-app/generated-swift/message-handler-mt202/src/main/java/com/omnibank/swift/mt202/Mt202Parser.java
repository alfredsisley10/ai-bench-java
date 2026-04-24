package com.omnibank.swift.mt202;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parser for SWIFT MT202 messages in the block-4 tag format.
 * This implementation understands the subset of tags typically
 * present in a MT202; unknown tags are preserved as raw strings.
 */
public final class Mt202Parser {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMdd");

    public Mt202Message parse(String rawBlock4) {
        Objects.requireNonNull(rawBlock4, "rawBlock4");
        Map<String, String> tags = tokenize(rawBlock4);
        List<String> rawTags = new ArrayList<>();
        tags.forEach((k, v) -> rawTags.add(k + "=" + v));

        String sender = tags.getOrDefault("sender", "UNKNOWNXXX");
        String receiver = tags.getOrDefault("receiver", "UNKNOWNXXX");
        String ref = tags.getOrDefault("20", "REF-UNKNOWN");
        String related = tags.getOrDefault("21", null);
        LocalDate valueDate = parseDate(tags.get("32A"));
        String ccy = parseCurrency(tags.get("32A"));
        BigDecimal amount = parseAmount(tags.get("32A"));
        String ordering = tags.getOrDefault("50K", tags.getOrDefault("50A", null));
        String beneficiary = tags.getOrDefault("59", tags.getOrDefault("58A", null));
        String remittance = tags.getOrDefault("70", null);

        return new Mt202Message(
                sender, receiver, ref, related, valueDate,
                ccy, amount, ordering, beneficiary, remittance,
                rawTags, Instant.now());
    }

    private Map<String, String> tokenize(String block4) {
        Map<String, String> out = new HashMap<>();
        for (String line : block4.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(":")) {
                int closing = trimmed.indexOf(':', 1);
                if (closing > 1) {
                    String tag = trimmed.substring(1, closing);
                    String value = trimmed.substring(closing + 1).trim();
                    out.put(tag, value);
                }
            } else if (trimmed.startsWith("sender=")) {
                out.put("sender", trimmed.substring(7));
            } else if (trimmed.startsWith("receiver=")) {
                out.put("receiver", trimmed.substring(9));
            }
        }
        return out;
    }

    private LocalDate parseDate(String tag32A) {
        if (tag32A == null || tag32A.length() < 6) return LocalDate.now();
        try {
            return LocalDate.parse(tag32A.substring(0, 6), DATE)
                    .plusYears(2000 - 1900);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    private String parseCurrency(String tag32A) {
        if (tag32A == null || tag32A.length() < 9) return "USD";
        String candidate = tag32A.substring(6, 9);
        try {
            Currency.getInstance(candidate);
            return candidate;
        } catch (IllegalArgumentException e) {
            return "USD";
        }
    }

    private BigDecimal parseAmount(String tag32A) {
        if (tag32A == null || tag32A.length() < 10) return BigDecimal.ZERO;
        String amountPart = tag32A.substring(9).replace(",", ".");
        try {
            return new BigDecimal(amountPart);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
