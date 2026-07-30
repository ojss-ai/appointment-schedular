// TASK: P1-T08
package com.scheduler.api.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Strategy router: picks the first {@link DispatchAdapter} whose
 * {@code supports(identifierType)} matches. Adding a channel (push,
 * WhatsApp) means adding one {@code @Component} — no router changes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DispatchService {

    private final List<DispatchAdapter> adapters;

    public DispatchResult dispatch(String identifier, String identifierType,
                                   String rawOtp, String tenantName) {
        return adapters.stream()
            .filter(a -> a.supports(identifierType))
            .findFirst()
            .map(a -> a.sendOtp(identifier, rawOtp, tenantName))
            .orElseGet(() -> {
                log.warn("No dispatch adapter for identifierType: {}", identifierType);
                return DispatchResult.failure(identifierType, "No adapter found");
            });
    }
}
