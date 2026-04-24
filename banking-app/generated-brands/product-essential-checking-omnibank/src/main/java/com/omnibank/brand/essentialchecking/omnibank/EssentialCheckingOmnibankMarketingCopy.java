package com.omnibank.brand.essentialchecking.omnibank;

/**
 * Marketing copy snippets per brand for EssentialChecking. Copy tone and
 * vocabulary shift dramatically — LegacyTrust speaks formally
 * in third person; FutureBank uses emoji and short sentences.
 */
public final class EssentialCheckingOmnibankMarketingCopy {

    public String heroHeadline() {
        return switch ("OMNIBANK") {
            case "OMNIBANK" -> "Your Essential Checking — done.";
            case "OMNIDIRECT" -> "Open Essential Checking in 4 minutes. Seriously.";
            case "SILVERBANK" -> "Thoughtful Essential Checking for life after 55.";
            case "STARTUPFI" -> "Essential Checking built for founders who can't wait.";
            case "LEGACYTRUST" -> "Legacy Trust is pleased to offer Essential Checking to qualified clients.";
            case "FUTUREBANK" -> "Saving is simple. Saving is fun. Essential Checking.";
            case "BLUEWATERBANK" -> "Essential Checking from your community bank.";
            default -> "Essential Checking from Omnibank.";
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
