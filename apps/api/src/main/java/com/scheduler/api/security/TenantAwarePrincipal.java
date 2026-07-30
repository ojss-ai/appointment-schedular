// TASK: P1-T05
package com.scheduler.api.security;

import com.scheduler.api.security.jwt.JwtClaims;

/** Spring Security principal carrying the validated JWT claims. */
public record TenantAwarePrincipal(JwtClaims claims) {
}
