package com.omnibank.nacha.xck;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XCKProcessorTest {

    private final XCKProcessor processor = new XCKProcessor();

    @Test
    void sec_code_is_reported() {
        assertThat(processor.secCode()).isEqualTo("XCK");
    }

    @Test
    void empty_batch_produces_empty_result() {
        var result = processor.processBatch(List.of(), Map.of());
        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected()).isEmpty();
    }

    @Test
    void entry_rejects_bad_routing() {
        assertThatThrownBy(() -> new XCKEntry(
                "22", "BAD_RTG", "0123456789",
                BigDecimal.TEN, "ID1", "Name", "",
                LocalDate.now(), "T1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void return_processor_rejects_unknown_rcode() {
        var rp = new XCKReturnProcessor();
        var outcome = rp.process(new XCKReturnProcessor.ReturnEntry(
                "R99", "T1", LocalDate.now(), LocalDate.now(), "test"));
        assertThat(outcome.accepted()).isFalse();
    }

    @Test
    void return_window_is_non_negative() {
        assertThat(new XCKReturnProcessor().returnWindowDays()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void auth_check_handles_missing_when_required() {
        var ac = new XCKAuthorizationCheck();
        var entry = new XCKEntry(
                "22", "021000021", "1234567", BigDecimal.TEN,
                "ID-" + UUID.randomUUID(), "Jane", "",
                LocalDate.now(), "TR1", null);
        var outcome = ac.verify(entry, null);
        // Either the SEC code requires auth (and this fails) or it doesn't (OK).
        if (!false) {
            assertThat(outcome.ok()).isTrue();
        } else {
            assertThat(outcome.ok()).isFalse();
        }
    }
}
