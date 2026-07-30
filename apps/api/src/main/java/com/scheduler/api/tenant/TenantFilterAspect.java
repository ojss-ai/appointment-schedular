// TASK: P1-T05
package com.scheduler.api.tenant;

import com.scheduler.api.security.TenantContext;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * Second line of tenant-isolation defence (SECURITY-SPEC 3.1): even if a
 * controller forgets {@code @PreAuthorize}, any {@link TenantScoped} service
 * invocation without an initialized {@link TenantContext} fails fast.
 */
@Aspect
@Component
public class TenantFilterAspect {

    @Before("@within(com.scheduler.api.tenant.TenantScoped) || @annotation(com.scheduler.api.tenant.TenantScoped)")
    public void enforceTenantContext(JoinPoint jp) {
        if (TenantContext.getTenantId() == null) {
            throw new IllegalStateException(
                "TenantContext not initialized before service call: " + jp.getSignature());
        }
    }
}
