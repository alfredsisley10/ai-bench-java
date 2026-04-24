package com.omnibank.brand.studentchecking.omnibank;

import java.util.List;

/**
 * Brand-shaped customer communication (emails, push notifications,
 * statement inserts) for StudentChecking under Omnibank. Different brands
 * use different senders, signature blocks, and contact routes.
 */
public final class StudentCheckingOmnibankCustomerCommunication {

    public String senderFromAddress() {
        return switch ("OMNIBANK") {
            case "OMNIBANK" -> "notifications@omnibank.example";
            case "OMNIDIRECT" -> "hey@omnidirect.example";
            case "SILVERBANK" -> "service@silverbank.example";
            case "STARTUPFI" -> "founders@startupfi.example";
            case "LEGACYTRUST" -> "office@legacytrust.example";
            case "FUTUREBANK" -> "hello@futurebank.example";
            case "BLUEWATERBANK" -> "local@bluewater.example";
            default -> "info@omnibank.example";
        };
    }

    public String signatureBlock() {
        return switch ("OMNIBANK") {
            case "LEGACYTRUST" -> "Sincerely,\nYour Relationship Manager\nLegacy Trust Private Client Services";
            case "FUTUREBANK" -> "Thanks!\n— FutureBank Team";
            case "OMNIDIRECT" -> "Cheers,\nOmniDirect";
            default -> "Best regards,\nOmnibank Customer Service";
        };
    }

    public String supportChannelPriority() {
        return switch ("OMNIBANK") {
            case "OMNIDIRECT" -> "IN_APP_CHAT,EMAIL,PHONE_BACK";
            case "SILVERBANK" -> "PHONE,BRANCH,EMAIL";
            case "LEGACYTRUST" -> "RELATIONSHIP_MANAGER_DIRECT";
            case "STARTUPFI" -> "SLACK_CONNECT,EMAIL,PHONE";
            default -> "PHONE,EMAIL,BRANCH";
        };
    }

    public List<String> postCloseSurveyQuestions() {
        return List.of(
                "How likely are you to recommend Omnibank to a friend or colleague? (0-10)",
                "What was the most helpful part of opening your StudentChecking account?",
                "Did anything slow you down during account opening?",
                "Is there a product or feature we don't offer that would have helped?"
        );
    }

    public String unsubscribeFooter() {
        return "To manage your email preferences, visit your Omnibank profile settings. "
                + "We never send marketing emails to customers who have opted out of promotional messages.";
    }
}
