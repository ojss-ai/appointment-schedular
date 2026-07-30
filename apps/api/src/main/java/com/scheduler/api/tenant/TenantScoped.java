// TASK: P1-T05
package com.scheduler.api.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @Service} (or a single method) as tenant-scoped:
 * {@link TenantFilterAspect} rejects any invocation made without an
 * initialized {@link com.scheduler.api.security.TenantContext}
 * (SECURITY-SPEC 3.1). Auth/bootstrap services that legitimately run before
 * authentication (OTP request, token issuance) are NOT annotated.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantScoped {
}
