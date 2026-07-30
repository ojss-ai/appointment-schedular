// TASK: P1-T05
package com.scheduler.api.tenant;

import com.scheduler.api.security.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SpEL bean backing {@code @PreAuthorize("@tenantGuard.check(#tenantId)")}
 * on every tenant-scoped controller method. Returns true only when the JWT
 * tenant matches the requested path tenant — mismatch yields 403
 * (TENANT_MISMATCH, SECURITY-SPEC 3.2).
 */
@Component("tenantGuard")
public class TenantGuard {

    public boolean check(UUID requestedTenantId) {
        UUID contextTenantId = TenantContext.getTenantId();
        if (contextTenantId == null || requestedTenantId == null) {
            return false;
        }
        return contextTenantId.equals(requestedTenantId);
    }
}
