package com.omnibank.brand.vacationclub.omnidirect;

/**
 * Marketing copy snippets per brand for VacationClub. Copy tone and
 * vocabulary shift dramatically — LegacyTrust speaks formally
 * in third person; FutureBank uses emoji and short sentences.
 */
public final class VacationClubOmnidirectMarketingCopy {

    public String heroHeadline() {
        return switch ("OMNIDIRECT") {
            case "OMNIBANK" -> "Your Vacation Club — done.";
            case "OMNIDIRECT" -> "Open Vacation Club in 4 minutes. Seriously.";
            case "SILVERBANK" -> "Thoughtful Vacation Club for life after 55.";
            case "STARTUPFI" -> "Vacation Club built for founders who can't wait.";
            case "LEGACYTRUST" -> "Legacy Trust is pleased to offer Vacation Club to qualified clients.";
            case "FUTUREBANK" -> "Saving is simple. Saving is fun. Vacation Club.";
            case "BLUEWATERBANK" -> "Vacation Club from your community bank.";
            default -> "Vacation Club from OmniDirect.";
        };
    }

    public String subheadline() {
        return switch ("OMNIDIRECT") {
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
        return switch ("OMNIDIRECT") {
            case "OMNIDIRECT", "STARTUPFI", "FUTUREBANK" -> "Open account";
            case "LEGACYTRUST" -> "Speak with an advisor";
            case "SILVERBANK", "BLUEWATERBANK" -> "Visit your branch";
            default -> "Get started";
        };
    }

    public String footerLegal() {
        return "OmniDirect is a service of Omnibank, N.A. "
                + "Member FDIC. Equal Housing Lender. "
                + "Deposits insured to the maximum amount allowed by law.";
    }
}
