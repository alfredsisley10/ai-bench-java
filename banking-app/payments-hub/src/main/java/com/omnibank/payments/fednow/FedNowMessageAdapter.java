package com.omnibank.payments.fednow;

import com.omnibank.payments.api.PaymentId;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.RoutingNumber;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapts internal payment representations to and from FedNow ISO 20022 message
 * formats. FedNow uses a subset of ISO 20022 with Fed-specific extensions.
 *
 * <p>Supported message types:
 * <ul>
 *   <li>pacs.008 — FI to FI Customer Credit Transfer (payment instruction)</li>
 *   <li>pacs.002 — FI to FI Payment Status Report (acknowledgement/rejection)</li>
 *   <li>camt.052 — Bank to Customer Account Report (balance query)</li>
 *   <li>admi.002 — System Event Notification (liquidity management)</li>
 * </ul>
 *
 * <p>FedNow-specific constraints:
 * <ul>
 *   <li>Maximum single payment: $500,000 (Fed limit as of launch; may increase)</li>
 *   <li>Only USD supported</li>
 *   <li>Messages must include Federal Reserve Bank master account reference</li>
 *   <li>Settlement is via Fed master accounts with immediate finality</li>
 * </ul>
 */
public class FedNowMessageAdapter {

    private static final Logger log = LoggerFactory.getLogger(FedNowMessageAdapter.class);

    private static final BigDecimal FEDNOW_MAX_AMOUNT = new BigDecimal("500000.00");
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    public sealed interface FedNowMessage permits
            CreditTransferMessage, PaymentStatusReport, AccountBalanceQuery, LiquidityManagementMessage {}

    public record CreditTransferMessage(
            String messageId,
            String endToEndId,
            String transactionId,
            RoutingNumber debtorAgent,
            String debtorName,
            String debtorAccount,
            RoutingNumber creditorAgent,
            String creditorName,
            String creditorAccount,
            Money amount,
            Instant creationDateTime,
            String remittanceInfo
    ) implements FedNowMessage {
        public CreditTransferMessage {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(endToEndId, "endToEndId");
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(debtorAgent, "debtorAgent");
            Objects.requireNonNull(debtorName, "debtorName");
            Objects.requireNonNull(debtorAccount, "debtorAccount");
            Objects.requireNonNull(creditorAgent, "creditorAgent");
            Objects.requireNonNull(creditorName, "creditorName");
            Objects.requireNonNull(creditorAccount, "creditorAccount");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(creationDateTime, "creationDateTime");
            if (amount.currency() != CurrencyCode.USD) {
                throw new IllegalArgumentException("FedNow only supports USD, got: " + amount.currency());
            }
            if (amount.amount().compareTo(FEDNOW_MAX_AMOUNT) > 0) {
                throw new IllegalArgumentException("Amount exceeds FedNow limit of $500,000: " + amount);
            }
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("Amount must be positive: " + amount);
            }
        }
    }

    public record PaymentStatusReport(
            String originalMessageId,
            String originalEndToEndId,
            StatusCode statusCode,
            String reasonCode,
            String additionalInfo,
            Instant statusDateTime
    ) implements FedNowMessage {
        public PaymentStatusReport {
            Objects.requireNonNull(originalMessageId, "originalMessageId");
            Objects.requireNonNull(statusCode, "statusCode");
            Objects.requireNonNull(statusDateTime, "statusDateTime");
        }
    }

    public record AccountBalanceQuery(
            String queryId,
            RoutingNumber requestingInstitution,
            String masterAccountRef,
            Instant queryDateTime
    ) implements FedNowMessage {
        public AccountBalanceQuery {
            Objects.requireNonNull(queryId, "queryId");
            Objects.requireNonNull(requestingInstitution, "requestingInstitution");
            Objects.requireNonNull(masterAccountRef, "masterAccountRef");
            Objects.requireNonNull(queryDateTime, "queryDateTime");
        }
    }

    public record LiquidityManagementMessage(
            String messageId,
            LiquidityAction action,
            Money amount,
            String masterAccountRef,
            String targetAccountRef,
            Instant requestDateTime
    ) implements FedNowMessage {
        public LiquidityManagementMessage {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(masterAccountRef, "masterAccountRef");
            Objects.requireNonNull(requestDateTime, "requestDateTime");
        }
    }

    public enum StatusCode {
        ACCP,  // Accepted Customer Profile
        ACSP,  // Accepted Settlement in Process
        ACSC,  // Accepted Settlement Completed
        RJCT,  // Rejected
        PDNG   // Pending
    }

    public enum LiquidityAction {
        FUND_FEDNOW_ACCOUNT,
        WITHDRAW_FROM_FEDNOW,
        TRANSFER_BETWEEN_ACCOUNTS,
        REQUEST_INTRADAY_CREDIT
    }

    /**
     * Converts an internal payment into a FedNow pacs.008 XML message.
     */
    public String toPacs008Xml(CreditTransferMessage msg) {
        log.debug("Building pacs.008 for FedNow: msgId={}, e2eId={}, amount={}",
                msg.messageId(), msg.endToEndId(), msg.amount());

        var remittance = msg.remittanceInfo() != null
                ? "        <RmtInf><Ustrd>%s</Ustrd></RmtInf>\n".formatted(escapeXml(msg.remittanceInfo()))
                : "";

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
                  <FIToFICstmrCdtTrf>
                    <GrpHdr>
                      <MsgId>%s</MsgId>
                      <CreDtTm>%s</CreDtTm>
                      <NbOfTxs>1</NbOfTxs>
                      <SttlmInf>
                        <SttlmMtd>CLRG</SttlmMtd>
                        <ClrSys><Prtry>FDN</Prtry></ClrSys>
                      </SttlmInf>
                    </GrpHdr>
                    <CdtTrfTxInf>
                      <PmtId>
                        <EndToEndId>%s</EndToEndId>
                        <TxId>%s</TxId>
                      </PmtId>
                      <IntrBkSttlmAmt Ccy="USD">%s</IntrBkSttlmAmt>
                      <IntrBkSttlmDt>%s</IntrBkSttlmDt>
                      <InstgAgt>
                        <FinInstnId><ClrSysMmbId><MmbId>%s</MmbId></ClrSysMmbId></FinInstnId>
                      </InstgAgt>
                      <InstdAgt>
                        <FinInstnId><ClrSysMmbId><MmbId>%s</MmbId></ClrSysMmbId></FinInstnId>
                      </InstdAgt>
                      <Dbtr><Nm>%s</Nm></Dbtr>
                      <DbtrAcct><Id><Othr><Id>%s</Id></Othr></Id></DbtrAcct>
                      <Cdtr><Nm>%s</Nm></Cdtr>
                      <CdtrAcct><Id><Othr><Id>%s</Id></Othr></Id></CdtrAcct>
                %s    </CdtTrfTxInf>
                  </FIToFICstmrCdtTrf>
                </Document>
                """.formatted(
                escapeXml(msg.messageId()),
                ISO_DT.format(msg.creationDateTime()),
                escapeXml(msg.endToEndId()),
                escapeXml(msg.transactionId()),
                msg.amount().amount().toPlainString(),
                ISO_DT.format(msg.creationDateTime()).substring(0, 10),
                msg.debtorAgent().raw(),
                msg.creditorAgent().raw(),
                escapeXml(msg.debtorName()),
                escapeXml(msg.debtorAccount()),
                escapeXml(msg.creditorName()),
                escapeXml(msg.creditorAccount()),
                remittance
        );
    }

    /**
     * Builds a camt.052 account balance query message for FedNow settlement account.
     */
    public String toCamt052Xml(AccountBalanceQuery query) {
        log.debug("Building camt.052 balance query: queryId={}, account={}",
                query.queryId(), query.masterAccountRef());

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.052.001.08">
                  <BkToCstmrAcctRpt>
                    <GrpHdr>
                      <MsgId>%s</MsgId>
                      <CreDtTm>%s</CreDtTm>
                    </GrpHdr>
                    <Rpt>
                      <Id>%s</Id>
                      <Acct>
                        <Id><Othr><Id>%s</Id></Othr></Id>
                        <Svcr>
                          <FinInstnId><ClrSysMmbId><MmbId>%s</MmbId></ClrSysMmbId></FinInstnId>
                        </Svcr>
                      </Acct>
                    </Rpt>
                  </BkToCstmrAcctRpt>
                </Document>
                """.formatted(
                escapeXml(query.queryId()),
                ISO_DT.format(query.queryDateTime()),
                escapeXml(query.queryId()),
                escapeXml(query.masterAccountRef()),
                query.requestingInstitution().raw()
        );
    }

    /**
     * Parses a pacs.002 payment status report from the FedNow network.
     * In production this would use a proper XML parser; simplified for clarity.
     */
    public PaymentStatusReport parsePacs002(String xml) {
        Objects.requireNonNull(xml, "xml");
        log.debug("Parsing pacs.002 status report, length={}", xml.length());

        // Production implementation would use StAX or JAXB for proper XML parsing.
        // This extracts key fields for the domain model.
        var originalMsgId = extractXmlElement(xml, "OrgnlMsgId");
        var originalE2eId = extractXmlElement(xml, "OrgnlEndToEndId");
        var statusStr = extractXmlElement(xml, "TxSts");
        var reasonCode = extractXmlElementOptional(xml, "Rsn");
        var additionalInfo = extractXmlElementOptional(xml, "AddtlInf");

        var statusCode = switch (statusStr) {
            case "ACCP" -> StatusCode.ACCP;
            case "ACSP" -> StatusCode.ACSP;
            case "ACSC" -> StatusCode.ACSC;
            case "RJCT" -> StatusCode.RJCT;
            case "PDNG" -> StatusCode.PDNG;
            default -> throw new IllegalArgumentException("Unknown FedNow status code: " + statusStr);
        };

        return new PaymentStatusReport(
                originalMsgId, originalE2eId, statusCode,
                reasonCode, additionalInfo, Instant.now());
    }

    /**
     * Builds a liquidity management message for FedNow account funding.
     */
    public String toLiquidityManagementXml(LiquidityManagementMessage msg) {
        log.debug("Building liquidity management message: action={}, amount={}, masterAccount={}",
                msg.action(), msg.amount(), msg.masterAccountRef());

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.050.001.05">
                  <LqdtyCdtTrf>
                    <MsgHdr>
                      <MsgId>%s</MsgId>
                      <CreDtTm>%s</CreDtTm>
                    </MsgHdr>
                    <LqdtyCdtTrf>
                      <LqdtyTrfId>
                        <EndToEndId>%s</EndToEndId>
                      </LqdtyTrfId>
                      <CdtrAcct><Id><Othr><Id>%s</Id></Othr></Id></CdtrAcct>
                      <TrfdAmt><AmtWthCcy Ccy="USD">%s</AmtWthCcy></TrfdAmt>
                      <DbtrAcct><Id><Othr><Id>%s</Id></Othr></Id></DbtrAcct>
                    </LqdtyCdtTrf>
                  </LqdtyCdtTrf>
                </Document>
                """.formatted(
                escapeXml(msg.messageId()),
                ISO_DT.format(msg.requestDateTime()),
                escapeXml(msg.messageId()),
                escapeXml(msg.masterAccountRef()),
                msg.amount().amount().toPlainString(),
                msg.targetAccountRef() != null ? escapeXml(msg.targetAccountRef()) : msg.masterAccountRef()
        );
    }

    /**
     * Creates a new credit transfer message from internal payment data.
     */
    public CreditTransferMessage buildCreditTransfer(
            PaymentId paymentId,
            RoutingNumber debtorAgent, String debtorName, String debtorAccount,
            RoutingNumber creditorAgent, String creditorName, String creditorAccount,
            Money amount, String remittanceInfo) {

        var msgId = "FEDNOW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var e2eId = paymentId.toString();
        var txId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        return new CreditTransferMessage(
                msgId, e2eId, txId,
                debtorAgent, debtorName, debtorAccount,
                creditorAgent, creditorName, creditorAccount,
                amount, Instant.now(), remittanceInfo);
    }

    private String extractXmlElement(String xml, String elementName) {
        var startTag = "<" + elementName + ">";
        var endTag = "</" + elementName + ">";
        int start = xml.indexOf(startTag);
        int end = xml.indexOf(endTag);
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("Missing required XML element: " + elementName);
        }
        return xml.substring(start + startTag.length(), end).trim();
    }

    private String extractXmlElementOptional(String xml, String elementName) {
        try {
            return extractXmlElement(xml, elementName);
        } catch (IllegalArgumentException e) {
            return null;
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
}
