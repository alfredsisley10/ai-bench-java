package com.omnibank.swift.mt202cov;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class Mt202COVHandlerTest {

    private final Mt202COVHandler handler = new Mt202COVHandler();

    @Test
    void empty_block4_is_rejected() {
        var result = handler.handle("");
        assertThat(result.accepted()).isFalse();
    }

    @Test
    void minimal_well_formed_message_returns_non_null_result() {
        String block4 = """
                sender=OMNIUS33XXX
                receiver=CHASUS33XXX
                :20:REF123
                :32A:260416USD5000,00
                :59:BENEFICIARY NAME
                :70:INVOICE 4242
                """;
        var result = handler.handle(block4);
        assertThat(result).isNotNull();
    }

    @Test
    void counters_increment_on_each_handle() {
        handler.handle("");
        handler.handle("");
        assertThat(handler.processedCount()).isEqualTo(2);
    }

    @Test
    void parser_accepts_basic_block() {
        var parser = new Mt202COVParser();
        String block4 = """
                sender=OMNIUS33
                receiver=CHASUS33
                :20:REF-MT202COV
                :32A:260416USD1000,00
                """;
        var msg = parser.parse(block4);
        assertThat(msg.messageReference()).isEqualTo("REF-MT202COV");
    }

    @Test
    void routing_rules_produce_a_destination() {
        var parser = new Mt202COVParser();
        String block4 = """
                sender=OMNIUS33
                receiver=CHASUS33
                :20:ROUTE1
                :32A:260416USD5000,00
                :59:BENEFICIARY
                """;
        var msg = parser.parse(block4);
        var rules = new Mt202COVRoutingRules();
        assertThat(rules.routeFor(msg)).isNotNull();
    }

    @Test
    void intra_group_routing_detected() {
        var rules = new Mt202COVRoutingRules();
        assertThat(rules.isIntraGroup("OMNIUS33XXX")).isTrue();
        assertThat(rules.isIntraGroup("CHASUS33XXX")).isFalse();
    }
}
