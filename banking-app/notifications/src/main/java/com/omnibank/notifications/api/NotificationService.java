package com.omnibank.notifications.api;

import com.omnibank.shared.domain.CustomerId;

public interface NotificationService {

    void send(CustomerId recipient, Channel channel, String template, java.util.Map<String, Object> data);

    enum Channel { EMAIL, SMS, PUSH, SECURE_INBOX }
}
