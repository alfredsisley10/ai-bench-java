package com.omnibank.shared.persistence;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores a {@link Money} into two columns via an embeddable-style holder.
 * Use the companion {@link MoneyAttribute} embeddable on fields — this
 * converter is the fallback for places that can't use embeddables.
 */
@Converter
public class MoneyConverter implements AttributeConverter<Money, String> {

    @Override
    public String convertToDatabaseColumn(Money attribute) {
        if (attribute == null) return null;
        return attribute.currency().iso4217() + "|" + attribute.amount().toPlainString();
    }

    @Override
    public Money convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        int sep = dbData.indexOf('|');
        if (sep < 0) {
            throw new IllegalStateException("Malformed money column: " + dbData);
        }
        CurrencyCode ccy = CurrencyCode.parse(dbData.substring(0, sep));
        return Money.of(dbData.substring(sep + 1), ccy);
    }
}
