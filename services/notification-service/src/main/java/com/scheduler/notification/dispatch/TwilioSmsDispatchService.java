// TASK: ATOM-KAFKA-008 (adapter pattern mirrors apps/api SmsDispatchAdapter)
package com.scheduler.notification.dispatch;

import com.scheduler.notification.domain.UserContact;
import com.scheduler.notification.repository.UserContactRepository;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import io.scheduler.events.BookingLifecycleEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Twilio SMS booking adapter. A user without a phone identifier is skipped;
 * a Twilio failure propagates so the consumer's error handler retries then
 * dead-letters (docs/KAFKA-SPEC.md section 6).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TwilioSmsDispatchService implements SmsDispatchService {

    private final TwilioRestClient twilioClient;
    private final DispatchProperties props;
    private final UserContactRepository userContactRepository;

    @Override
    public void sendConfirmation(BookingLifecycleEvent event) {
        Optional<String> phone = userContactRepository
            .findByIdAndTenantId(
                UUID.fromString(event.getUserId()), UUID.fromString(event.getTenantId()))
            .filter(u -> UserContact.TYPE_PHONE.equalsIgnoreCase(u.getIdentifierType()))
            .map(UserContact::getIdentifier);
        if (phone.isEmpty()) {
            log.info("No phone identifier for userId={} tenantId={} — SMS skipped",
                event.getUserId(), event.getTenantId());
            return;
        }
        Message.creator(
            new PhoneNumber(phone.get()),
            new PhoneNumber(props.twilioFromNumber()),
            "Booking confirmed for " + event.getSlotStart() + ". Ref " + event.getBookingId()
        ).create(twilioClient);
        log.info("SMS dispatched bookingId={}", event.getBookingId());
    }
}
