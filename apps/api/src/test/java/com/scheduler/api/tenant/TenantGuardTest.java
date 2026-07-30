// TASK: P1-T05
package com.scheduler.api.tenant;

import com.scheduler.api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantGuardTest {

    private final TenantGuard guard = new TenantGuard();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldAllow_whenTenantMatches() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId, UUID.randomUUID(), List.of("ROLE_CUSTOMER"));
        assertThat(guard.check(tenantId)).isTrue();
    }

    @Test
    void shouldDeny_whenTenantDiffers() {
        TenantContext.set(UUID.randomUUID(), UUID.randomUUID(), List.of("ROLE_CUSTOMER"));
        assertThat(guard.check(UUID.randomUUID())).isFalse();
    }

    @Test
    void shouldDeny_whenNoContext() {
        assertThat(guard.check(UUID.randomUUID())).isFalse();
    }
}
