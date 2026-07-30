// TASK: P1-T08
package com.scheduler.api.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * AWS SES v2 email adapter. Failures are logged and returned as
 * {@code DispatchResult.failure} — never thrown, because the OTP has already
 * been generated and the auth request must still succeed (202).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailDispatchAdapter implements DispatchAdapter {

    static final String CHANNEL = "email";

    private final SesV2Client sesClient;
    private final DispatchProperties props;

    @Override
    public DispatchResult sendOtp(String email, String rawOtp, String tenantName) {
        try {
            sesClient.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(props.sesFromAddress())
                .destination(d -> d.toAddresses(email))
                .content(c -> c.simple(s -> s
                    .subject(sub -> sub.data("Your " + tenantName + " verification code"))
                    .body(b -> b
                        .text(t -> t.data("Your code is: " + rawOtp + "\nExpires in 5 minutes."))
                        .html(h -> h.data(buildOtpHtml(tenantName, rawOtp)))
                    )
                ))
                .build());
            return DispatchResult.success(CHANNEL);
        } catch (RuntimeException e) {
            log.error("SES dispatch failed: {}", e.getMessage());
            return DispatchResult.failure(CHANNEL, e.getMessage());
        }
    }

    @Override
    public DispatchResult sendMagicLink(String email, String magicLink, String tenantName) {
        try {
            sesClient.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(props.sesFromAddress())
                .destination(d -> d.toAddresses(email))
                .content(c -> c.simple(s -> s
                    .subject(sub -> sub.data("Your " + tenantName + " sign-in link"))
                    .body(b -> b
                        .text(t -> t.data("Sign in: " + magicLink + "\nExpires in 5 minutes."))
                        .html(h -> h.data(buildMagicLinkHtml(tenantName, magicLink)))
                    )
                ))
                .build());
            return DispatchResult.success(CHANNEL);
        } catch (RuntimeException e) {
            log.error("SES magic-link dispatch failed: {}", e.getMessage());
            return DispatchResult.failure(CHANNEL, e.getMessage());
        }
    }

    @Override
    public boolean supports(String identifierType) {
        return CHANNEL.equalsIgnoreCase(identifierType);
    }

    private String buildOtpHtml(String tenantName, String otp) {
        return """
            <html><body>
            <h2>%s verification</h2>
            <p>Your verification code is:</p>
            <h1 style="letter-spacing:4px">%s</h1>
            <p>This code expires in 5 minutes.</p>
            </body></html>
            """.formatted(tenantName, otp);
    }

    private String buildMagicLinkHtml(String tenantName, String magicLink) {
        return """
            <html><body>
            <h2>%s sign-in</h2>
            <p><a href="%s">Click here to sign in</a></p>
            <p>This link expires in 5 minutes.</p>
            </body></html>
            """.formatted(tenantName, magicLink);
    }
}
