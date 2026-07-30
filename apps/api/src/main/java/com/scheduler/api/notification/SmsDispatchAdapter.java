// TASK: P1-T08
package com.scheduler.api.notification;

import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Twilio SMS adapter. Failures return {@code DispatchResult.failure} —
 * never propagated (the auth flow must stay 202).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SmsDispatchAdapter implements DispatchAdapter {

    static final String CHANNEL = "sms";

    private final TwilioRestClient twilioClient;
    private final DispatchProperties props;

    @Override
    public DispatchResult sendOtp(String phone, String rawOtp, String tenantName) {
        try {
            Message.creator(
                new PhoneNumber(phone),
                new PhoneNumber(props.twilioFromNumber()),
                tenantName + " code: " + rawOtp + " (expires 5 min)"
            ).create(twilioClient);
            return DispatchResult.success(CHANNEL);
        } catch (RuntimeException e) {
            log.error("Twilio dispatch failed: {}", e.getMessage());
            return DispatchResult.failure(CHANNEL, e.getMessage());
        }
    }

    @Override
    public DispatchResult sendMagicLink(String phone, String magicLink, String tenantName) {
        throw new UnsupportedOperationException("Magic link via SMS not supported");
    }

    @Override
    public boolean supports(String identifierType) {
        return "phone".equalsIgnoreCase(identifierType);
    }
}
