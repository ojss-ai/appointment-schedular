// TASK: ATOM-KAFKA-008 (adapter pattern mirrors apps/api EmailDispatchAdapter)
package com.scheduler.notification.dispatch;

import com.scheduler.notification.domain.UserContact;
import com.scheduler.notification.repository.UserContactRepository;
import io.scheduler.events.BookingLifecycleEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

import java.util.Optional;
import java.util.UUID;

/**
 * AWS SES v2 booking email adapter. Recipient is resolved from the users
 * table by (userId, tenantId) — tenant-filtered per ADR-004. A user without
 * an email identifier is skipped (logged), not an error; an SES failure is
 * rethrown so the consumer's error handler retries then dead-letters.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SesEmailDispatchService implements EmailDispatchService {

    private final SesV2Client sesClient;
    private final DispatchProperties props;
    private final UserContactRepository userContactRepository;

    @Override
    public void sendConfirmation(BookingLifecycleEvent event) {
        resolveEmail(event).ifPresent(email -> send(email,
            "Your booking is confirmed",
            """
            Your booking is confirmed.

            Reference: %s
            Starts:    %s
            Ends:      %s
            """.formatted(event.getBookingId(), event.getSlotStart(), event.getSlotEnd())));
    }

    @Override
    public void sendCancellation(BookingLifecycleEvent event) {
        resolveEmail(event).ifPresent(email -> send(email,
            "Your booking was cancelled",
            """
            Your booking has been cancelled.

            Reference: %s
            Was scheduled for: %s
            """.formatted(event.getBookingId(), event.getSlotStart())));
    }

    private Optional<String> resolveEmail(BookingLifecycleEvent event) {
        Optional<String> email = userContactRepository
            .findByIdAndTenantId(
                UUID.fromString(event.getUserId()), UUID.fromString(event.getTenantId()))
            .filter(u -> UserContact.TYPE_EMAIL.equalsIgnoreCase(u.getIdentifierType()))
            .map(UserContact::getIdentifier);
        if (email.isEmpty()) {
            log.info("No email identifier for userId={} tenantId={} — email skipped",
                event.getUserId(), event.getTenantId());
        }
        return email;
    }

    private void send(String recipient, String subject, String body) {
        sesClient.sendEmail(SendEmailRequest.builder()
            .fromEmailAddress(props.sesFromAddress())
            .destination(d -> d.toAddresses(recipient))
            .content(c -> c.simple(s -> s
                .subject(sub -> sub.data(subject))
                .body(b -> b.text(t -> t.data(body)))))
            .build());
        log.info("Email dispatched subject=\"{}\"", subject);
    }
}
