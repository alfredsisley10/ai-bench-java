package com.omnibank.payments.rtp;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.RoutingNumber;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Constructs ISO 20022 pain.001 messages for RTP network submission.
 *
 * <p>The Clearing House RTP network mandates strict compliance with ISO 20022
 * message formats. This builder enforces field-level validation, character set
 * restrictions (FINplus safe characters), and structured remittance information
 * before producing the XML payload.
 *
 * <p>Key constraints:
 * <ul>
 *   <li>Maximum payment amount: $1,000,000 (TCH network limit)</li>
 *   <li>Character set restricted to FINplus safe characters (no &lt; &gt; &amp; etc.)</li>
 *   <li>Structured remittance requires invoice reference or purchase order</li>
 *   <li>Message ID must be unique across the network (UUID-based)</li>
 * </ul>
 */
public final class RtpMessageBuilder {

    private static final BigDecimal RTP_MAX_AMOUNT = new BigDecimal("1000000.00");
    private static final int MAX_REMITTANCE_LINES = 9999;
    private static final Pattern FINPLUS_SAFE = Pattern.compile("^[a-zA-Z0-9 .,\\-()'/+:?!\"#%&*;=@\\[\\]{}|~^`_\\\\]*$");
    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    public sealed interface RemittanceInfo permits UnstructuredRemittance, StructuredRemittance {}

    public record UnstructuredRemittance(String text) implements RemittanceInfo {
        public UnstructuredRemittance {
            Objects.requireNonNull(text, "remittance text");
            if (text.length() > 140) {
                throw new IllegalArgumentException("Unstructured remittance exceeds 140 characters");
            }
            validateCharacterSet(text, "remittance text");
        }
    }

    public record StructuredRemittance(
            String invoiceNumber,
            String purchaseOrderRef,
            Money invoiceAmount,
            String additionalInfo
    ) implements RemittanceInfo {
        public StructuredRemittance {
            if (invoiceNumber == null && purchaseOrderRef == null) {
                throw new IllegalArgumentException("Structured remittance requires invoice number or PO reference");
            }
            if (invoiceNumber != null) validateCharacterSet(invoiceNumber, "invoiceNumber");
            if (purchaseOrderRef != null) validateCharacterSet(purchaseOrderRef, "purchaseOrderRef");
            if (additionalInfo != null) {
                if (additionalInfo.length() > 500) {
                    throw new IllegalArgumentException("Additional info exceeds 500 characters");
                }
                validateCharacterSet(additionalInfo, "additionalInfo");
            }
        }
    }

    public record RtpParty(
            String name,
            RoutingNumber routingNumber,
            String accountNumber,
            String postalAddress
    ) {
        public RtpParty {
            Objects.requireNonNull(name, "party name");
            Objects.requireNonNull(routingNumber, "routing number");
            Objects.requireNonNull(accountNumber, "account number");
            if (name.length() > 140) {
                throw new IllegalArgumentException("Party name exceeds 140 characters");
            }
            validateCharacterSet(name, "party name");
            validateCharacterSet(accountNumber, "account number");
        }
    }

    private final String messageId;
    private final Instant creationDateTime;
    private RtpParty debtor;
    private RtpParty creditor;
    private Money amount;
    private RemittanceInfo remittanceInfo;
    private String endToEndId;
    private final List<String> validationErrors = new ArrayList<>();

    public RtpMessageBuilder() {
        this.messageId = "RTP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        this.creationDateTime = Instant.now();
    }

    public RtpMessageBuilder(Instant creationDateTime) {
        this.messageId = "RTP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        this.creationDateTime = Objects.requireNonNull(creationDateTime, "creationDateTime");
    }

    public RtpMessageBuilder debtor(RtpParty debtor) {
        this.debtor = Objects.requireNonNull(debtor, "debtor");
        return this;
    }

    public RtpMessageBuilder creditor(RtpParty creditor) {
        this.creditor = Objects.requireNonNull(creditor, "creditor");
        return this;
    }

    public RtpMessageBuilder amount(Money amount) {
        this.amount = Objects.requireNonNull(amount, "amount");
        return this;
    }

    public RtpMessageBuilder remittance(RemittanceInfo info) {
        this.remittanceInfo = Objects.requireNonNull(info, "remittance info");
        return this;
    }

    public RtpMessageBuilder endToEndId(String id) {
        Objects.requireNonNull(id, "endToEndId");
        if (id.length() > 35) {
            throw new IllegalArgumentException("End-to-end ID exceeds 35 characters");
        }
        validateCharacterSet(id, "endToEndId");
        this.endToEndId = id;
        return this;
    }

    /**
     * Validates all fields and builds the ISO 20022 pain.001 XML message.
     *
     * @return the complete XML message payload
     * @throws RtpMessageValidationException if any validation rules are violated
     */
    public String build() {
        validationErrors.clear();

        if (debtor == null) validationErrors.add("Debtor party is required");
        if (creditor == null) validationErrors.add("Creditor party is required");
        if (amount == null) {
            validationErrors.add("Payment amount is required");
        } else {
            if (!amount.isPositive()) validationErrors.add("Payment amount must be positive");
            if (amount.amount().compareTo(RTP_MAX_AMOUNT) > 0) {
                validationErrors.add("Payment amount exceeds RTP network limit of $1,000,000");
            }
        }
        if (endToEndId == null) {
            endToEndId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        if (!validationErrors.isEmpty()) {
            throw new RtpMessageValidationException(validationErrors);
        }

        return renderPain001Xml();
    }

    private String renderPain001Xml() {
        var remittanceXml = renderRemittance();
        var postalDbtr = debtor.postalAddress() != null
                ? "          <PstlAdr><StrtNm>%s</StrtNm></PstlAdr>%n".formatted(escapeXml(debtor.postalAddress()))
                : "";
        var postalCdtr = creditor.postalAddress() != null
                ? "          <PstlAdr><StrtNm>%s</StrtNm></PstlAdr>%n".formatted(escapeXml(creditor.postalAddress()))
                : "";

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09">
                  <CstmrCdtTrfInitn>
                    <GrpHdr>
                      <MsgId>%s</MsgId>
                      <CreDtTm>%s</CreDtTm>
                      <NbOfTxs>1</NbOfTxs>
                      <CtrlSum>%s</CtrlSum>
                      <InitgPty>
                        <Nm>%s</Nm>
                      </InitgPty>
                    </GrpHdr>
                    <PmtInf>
                      <PmtInfId>%s</PmtInfId>
                      <PmtMtd>TRF</PmtMtd>
                      <NbOfTxs>1</NbOfTxs>
                      <PmtTpInf>
                        <SvcLvl><Cd>SDVA</Cd></SvcLvl>
                        <LclInstrm><Prtry>REAL_TIME_PAYMENT</Prtry></LclInstrm>
                      </PmtTpInf>
                      <ReqdExctnDt><Dt>%s</Dt></ReqdExctnDt>
                      <Dbtr>
                        <Nm>%s</Nm>
                %s      </Dbtr>
                      <DbtrAcct>
                        <Id><Othr><Id>%s</Id></Othr></Id>
                      </DbtrAcct>
                      <DbtrAgt>
                        <FinInstnId><ClrSysMmbId><MmbId>%s</MmbId></ClrSysMmbId></FinInstnId>
                      </DbtrAgt>
                      <CdtTrfTxInf>
                        <PmtId>
                          <EndToEndId>%s</EndToEndId>
                        </PmtId>
                        <Amt>
                          <InstdAmt Ccy="%s">%s</InstdAmt>
                        </Amt>
                        <CdtrAgt>
                          <FinInstnId><ClrSysMmbId><MmbId>%s</MmbId></ClrSysMmbId></FinInstnId>
                        </CdtrAgt>
                        <Cdtr>
                          <Nm>%s</Nm>
                %s        </Cdtr>
                        <CdtrAcct>
                          <Id><Othr><Id>%s</Id></Othr></Id>
                        </CdtrAcct>
                %s      </CdtTrfTxInf>
                    </PmtInf>
                  </CstmrCdtTrfInitn>
                </Document>
                """.formatted(
                escapeXml(messageId),
                ISO_DATETIME.format(creationDateTime),
                amount.amount().toPlainString(),
                escapeXml(debtor.name()),
                "PMTINF-" + messageId,
                ISO_DATETIME.format(creationDateTime).substring(0, 10),
                escapeXml(debtor.name()),
                postalDbtr,
                escapeXml(debtor.accountNumber()),
                debtor.routingNumber().raw(),
                escapeXml(endToEndId),
                amount.currency().iso4217(),
                amount.amount().toPlainString(),
                creditor.routingNumber().raw(),
                escapeXml(creditor.name()),
                postalCdtr,
                escapeXml(creditor.accountNumber()),
                remittanceXml
        );
    }

    private String renderRemittance() {
        if (remittanceInfo == null) return "";

        // JDK 17 cross-compat: switch patterns (JEP 441) aren't in 17.
        // Rewritten as instanceof chain (JEP 394 is stable in 17).
        if (remittanceInfo instanceof UnstructuredRemittance u) {
            return """
                            <RmtInf>
                              <Ustrd>%s</Ustrd>
                            </RmtInf>
                    """.formatted(escapeXml(u.text()));
        }
        if (remittanceInfo instanceof StructuredRemittance s) {
            var sb = new StringBuilder();
            sb.append("          <RmtInf>\n            <Strd>\n");
            if (s.invoiceNumber() != null) {
                sb.append("              <RfrdDocInf><Nb>%s</Nb></RfrdDocInf>\n"
                        .formatted(escapeXml(s.invoiceNumber())));
            }
            if (s.purchaseOrderRef() != null) {
                sb.append("              <RfrdDocInf><Tp><CdOrPrtry><Cd>PUOR</Cd></CdOrPrtry></Tp><Nb>%s</Nb></RfrdDocInf>\n"
                        .formatted(escapeXml(s.purchaseOrderRef())));
            }
            if (s.invoiceAmount() != null) {
                sb.append("              <RfrdDocAmt><DuePyblAmt Ccy=\"%s\">%s</DuePyblAmt></RfrdDocAmt>\n"
                        .formatted(s.invoiceAmount().currency().iso4217(), s.invoiceAmount().amount().toPlainString()));
            }
            if (s.additionalInfo() != null) {
                sb.append("              <AddtlRmtInf>%s</AddtlRmtInf>\n"
                        .formatted(escapeXml(s.additionalInfo())));
            }
            sb.append("            </Strd>\n          </RmtInf>\n");
            return sb.toString();
        }
        throw new IllegalStateException("Unknown RemittanceInfo subtype: " + remittanceInfo.getClass());
    }

    private static void validateCharacterSet(String value, String fieldName) {
        if (!FINPLUS_SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Field '%s' contains characters outside FINplus safe character set: %s"
                            .formatted(fieldName, value));
        }
    }

    private static String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    public static final class RtpMessageValidationException extends RuntimeException {
        private final List<String> errors;

        public RtpMessageValidationException(List<String> errors) {
            super("RTP message validation failed: " + String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }
}
