package com.omnibank.brand.rewardschecking.silverbank;

/**
 * Marketing copy snippets per brand for RewardsChecking. Copy tone and
 * vocabulary shift dramatically — LegacyTrust speaks formally
 * in third person; FutureBank uses emoji and short sentences.
 */
public final class RewardsCheckingSilverbankMarketingCopy {

    public String heroHeadline() {
        return switch ("SILVERBANK") {
            case "OMNIBANK" -> "Your Rewards Checking — done.";
            case "OMNIDIRECT" -> "Open Rewards Checking in 4 minutes. Seriously.";
            case "SILVERBANK" -> "Thoughtful Rewards Checking for life after 55.";
            case "STARTUPFI" -> "Rewards Checking built for founders who can't wait.";
            case "LEGACYTRUST" -> "Legacy Trust is pleased to offer Rewards Checking to qualified clients.";
            case "FUTUREBANK" -> "Saving is simple. Saving is fun. Rewards Checking.";
            case "BLUEWATERBANK" -> "Rewards Checking from your community bank.";
            default -> "Rewards Checking from SilverBank.";
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
