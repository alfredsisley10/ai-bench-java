package com.omnibank.brand.cdladder12month.bluewaterbank;

/**
 * Marketing copy snippets per brand for CdLadder12Month. Copy tone and
 * vocabulary shift dramatically — LegacyTrust speaks formally
 * in third person; FutureBank uses emoji and short sentences.
 */
public final class CdLadder12MonthBluewaterbankMarketingCopy {

    public String heroHeadline() {
        return switch ("BLUEWATERBANK") {
            case "OMNIBANK" -> "Your Cd Ladder12Month — done.";
            case "OMNIDIRECT" -> "Open Cd Ladder12Month in 4 minutes. Seriously.";
            case "SILVERBANK" -> "Thoughtful Cd Ladder12Month for life after 55.";
            case "STARTUPFI" -> "Cd Ladder12Month built for founders who can't wait.";
            case "LEGACYTRUST" -> "Legacy Trust is pleased to offer Cd Ladder12Month to qualified clients.";
            case "FUTUREBANK" -> "Saving is simple. Saving is fun. Cd Ladder12Month.";
            case "BLUEWATERBANK" -> "Cd Ladder12Month from your community bank.";
            default -> "Cd Ladder12Month from BlueWater Community Bank.";
        };
    }

    public String subheadline() {
        return switch ("BLUEWATERBANK") {
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
        return switch ("BLUEWATERBANK") {
            case "OMNIDIRECT", "STARTUPFI", "FUTUREBANK" -> "Open account";
            case "LEGACYTRUST" -> "Speak with an advisor";
            case "SILVERBANK", "BLUEWATERBANK" -> "Visit your branch";
            default -> "Get started";
        };
    }

    public String footerLegal() {
        return "BlueWater Community Bank is a service of Omnibank, N.A. "
                + "Member FDIC. Equal Housing Lender. "
                + "Deposits insured to the maximum amount allowed by law.";
    }
}
