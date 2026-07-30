// TASK: P1-T09
package com.scheduler.api.tenant;

/** Raised when a tenantSlug does not resolve to an active tenant. */
public class TenantNotFoundException extends RuntimeException {

    public TenantNotFoundException(String slug) {
        super("Tenant not found: " + slug);
    }
}
