package com.omnibank.brand.seniorchecking.omnibank;

/**
 * Marketing copy snippets per brand for SeniorChecking. Copy tone and
 * vocabulary shift dramatically — LegacyTrust speaks formally
 * in third person; FutureBank uses emoji and short sentences.
 */
public final class SeniorCheckingOmnibankMarketingCopy {

    public String heroHeadline() {
        return switch ("OMNIBANK") {
            case "OMNIBANK" -> "Your Senior Checking — done.";
            case "OMNIDIRECT" -> "Open Senior Checking in 4 minutes. Seriously.";
            case "SILVERBANK" -> "Thoughtful Senior Checking for life after 55.";
            case "STARTUPFI" -> "Senior Checking built for founders who can't wait.";
            case "LEGACYTRUST" -> "Legacy Trust is pleased to offer Senior Checking to qualified clients.";
            case "FUTUREBANK" -> "Saving is simple. Saving is fun. Senior Checking.";
            case "BLUEWATERBANK" -> "Senior Checking from your community bank.";
            default -> "Senior Checking from Omnibank.";
        };
    }

    public String subheadline() {
        return switch ("OMNIBANK") {
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
        return switch ("OMNIBANK") {
            case "OMNIDIRECT", "STARTUPFI", "FUTUREBANK" -> "Open account";
            case "LEGACYTRUST" -> "Speak with an advisor";
            case "SILVERBANK", "BLUEWATERBANK" -> "Visit your branch";
            default -> "Get started";
        };
    }

    public String footerLegal() {
        return "Omnibank is a service of Omnibank, N.A. "
                + "Member FDIC. Equal Housing Lender. "
                + "Deposits insured to the maximum amount allowed by law.";
    }
}
