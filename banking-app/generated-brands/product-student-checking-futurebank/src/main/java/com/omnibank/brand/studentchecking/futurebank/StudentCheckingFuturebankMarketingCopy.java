package com.omnibank.brand.studentchecking.futurebank;

/**
 * Marketing copy snippets per brand for StudentChecking. Copy tone and
 * vocabulary shift dramatically — LegacyTrust speaks formally
 * in third person; FutureBank uses emoji and short sentences.
 */
public final class StudentCheckingFuturebankMarketingCopy {

    public String heroHeadline() {
        return switch ("FUTUREBANK") {
            case "OMNIBANK" -> "Your Student Checking — done.";
            case "OMNIDIRECT" -> "Open Student Checking in 4 minutes. Seriously.";
            case "SILVERBANK" -> "Thoughtful Student Checking for life after 55.";
            case "STARTUPFI" -> "Student Checking built for founders who can't wait.";
            case "LEGACYTRUST" -> "Legacy Trust is pleased to offer Student Checking to qualified clients.";
            case "FUTUREBANK" -> "Saving is simple. Saving is fun. Student Checking.";
            case "BLUEWATERBANK" -> "Student Checking from your community bank.";
            default -> "Student Checking from FutureBank.";
        };
    }

    public String subheadline() {
        return switch ("FUTUREBANK") {
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
        return switch ("FUTUREBANK") {
            case "OMNIDIRECT", "STARTUPFI", "FUTUREBANK" -> "Open account";
            case "LEGACYTRUST" -> "Speak with an advisor";
            case "SILVERBANK", "BLUEWATERBANK" -> "Visit your branch";
            default -> "Get started";
        };
    }

    public String footerLegal() {
        return "FutureBank is a service of Omnibank, N.A. "
                + "Member FDIC. Equal Housing Lender. "
                + "Deposits insured to the maximum amount allowed by law.";
    }
}
