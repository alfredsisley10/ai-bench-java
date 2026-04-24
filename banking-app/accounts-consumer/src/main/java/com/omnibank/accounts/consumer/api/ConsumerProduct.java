package com.omnibank.accounts.consumer.api;

import com.omnibank.shared.domain.Percent;
import com.omnibank.shared.domain.Tenor;

/**
 * Product catalog for consumer deposits. Real banks often store this in a
 * Product Master DB — Omnibank keeps it in code because the set is stable
 * and changes weekly via engineering, not daily via a product manager.
 */
public enum ConsumerProduct {

    CHECKING_BASIC      (Kind.CHECKING,   Percent.ofBps(0),    null),
    CHECKING_PREMIUM    (Kind.CHECKING,   Percent.ofBps(10),   null),
    SAVINGS_STANDARD    (Kind.SAVINGS,    Percent.ofBps(45),   null),
    SAVINGS_HIGH_YIELD  (Kind.SAVINGS,    Percent.ofBps(425),  null),
    CD_6M               (Kind.CD,         Percent.ofBps(475),  Tenor.parse("6M")),
    CD_12M              (Kind.CD,         Percent.ofBps(500),  Tenor.parse("12M")),
    CD_60M              (Kind.CD,         Percent.ofBps(395),  Tenor.parse("60M"));

    public enum Kind { CHECKING, SAVINGS, CD }

    public final Kind kind;
    public final Percent aprBase;
    public final Tenor term;

    ConsumerProduct(Kind kind, Percent aprBase, Tenor term) {
        this.kind = kind;
        this.aprBase = aprBase;
        this.term = term;
    }

    public boolean isMaturing() {
        return kind == Kind.CD;
    }
}
