package com.omnibank.brand.traditionalira.silverbank;

/**
 * Marketing copy snippets per brand for TraditionalIra. Copy tone and
 * vocabulary shift dramatically — LegacyTrust speaks formally
 * in third person; FutureBank uses emoji and short sentences.
 */
public final class TraditionalIraSilverbankMarketingCopy {

    public String heroHeadline() {
        return switch ("SILVERBANK") {
            case "OMNIBANK" -> "Your Traditional Ira — done.";
            case "OMNIDIRECT" -> "Open Traditional Ira in 4 minutes. Seriously.";
            case "SILVERBANK" -> "Thoughtful Traditional Ira for life after 55.";
            case "STARTUPFI" -> "Traditional Ira built for founders who can't wait.";
            case "LEGACYTRUST" -> "Legacy Trust is pleased to offer Traditional Ira to qualified clients.";
            case "FUTUREBANK" -> "Saving is simple. Saving is fun. Traditional Ira.";
            case "BLUEWATERBANK" -> "Traditional Ira from your community bank.";
            default -> "Traditional Ira from SilverBank.";
        };
    }

    public String subheadline() {
        return switch ("SILVERBANK") {
            case "OMNIBANK" -> "Transparent pricing. Full-service banking. Every channel.";
            case "OMNIDIRECT" -> "No branches, no fees, no nonsense.";
            case "SILVERBANK" -> "Real people answering the phone. Every call.";
            case "STARTUPFI" -> "Designed with founders. Trusted by thousands of startups.";
            case "LEGACYTRUST" -> "A century of private client service.";
            case "FUTUREBANK" -> "Automatic round-ups. Parental controls. Interest that actually pays.";
            case "BLUEWATERBANK" -> "Decisions made here, by people who live here.";
            default -> "Banking that fits your life.";
        };
    }

    public String callToActionVerb() {
        return switch ("SILVERBANK") {
            case "OMNIDIRECT", "STARTUPFI", "FUTUREBANK" -> "Open account";
            case "LEGACYTRUST" -> "Speak with an advisor";
            case "SILVERBANK", "BLUEWATERBANK" -> "Visit your branch";
            default -> "Get started";
        };
    }

    public String footerLegal() {
        return "SilverBank is a service of Omnibank, N.A. "
                + "Member FDIC. Equal Housing Lender. "
                + "Deposits insured to the maximum amount allowed by law.";
    }
}
