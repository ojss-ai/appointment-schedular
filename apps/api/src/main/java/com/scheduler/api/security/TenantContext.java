// TASK: P1-T05
package com.scheduler.api.security;

import java.util.List;
import java.util.UUID;

/**
 * ThreadLocal holder for the authenticated tenant/user of the current
 * request (ADR-004: row-level multi-tenancy). Populated by
 * {@link JwtAuthFilter}; cleared in its {@code finally} block so pooled
 * threads never leak another tenant's identity.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> ROLES = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId, UUID userId, List<String> roles) {
        TENANT_ID.set(tenantId);
        USER_ID.set(userId);
        ROLES.set(roles);
    }

    public static UUID getTenantId() {
        return TENANT_ID.get();
    }

    public static UUID getUserId() {
        return USER_ID.get();
    }

    public static List<String> getRoles() {
        return ROLES.get();
    }

    public static void clear() {
        TENANT_ID.remove();
        USER_ID.remove();
        ROLES.remove();
    }
}
