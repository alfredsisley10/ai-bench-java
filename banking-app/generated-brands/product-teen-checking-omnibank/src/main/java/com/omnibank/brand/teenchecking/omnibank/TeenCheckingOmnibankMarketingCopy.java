package com.omnibank.brand.teenchecking.omnibank;

/**
 * Marketing copy snippets per brand for TeenChecking. Copy tone and
 * vocabulary shift dramatically — LegacyTrust speaks formally
 * in third person; FutureBank uses emoji and short sentences.
 */
public final class TeenCheckingOmnibankMarketingCopy {

    public String heroHeadline() {
        return switch ("OMNIBANK") {
            case "OMNIBANK" -> "Your Teen Checking — done.";
            case "OMNIDIRECT" -> "Open Teen Checking in 4 minutes. Seriously.";
            case "SILVERBANK" -> "Thoughtful Teen Checking for life after 55.";
            case "STARTUPFI" -> "Teen Checking built for founders who can't wait.";
            case "LEGACYTRUST" -> "Legacy Trust is pleased to offer Teen Checking to qualified clients.";
            case "FUTUREBANK" -> "Saving is simple. Saving is fun. Teen Checking.";
            case "BLUEWATERBANK" -> "Teen Checking from your community bank.";
            default -> "Teen Checking from Omnibank.";
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
