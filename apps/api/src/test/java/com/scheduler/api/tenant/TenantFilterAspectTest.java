// TASK: P1-T05
package com.scheduler.api.tenant;

import com.scheduler.api.security.TenantContext;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantFilterAspectTest {

    private final TenantFilterAspect aspect = new TenantFilterAspect();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldThrow_whenServiceCalledWithoutContext() {
        JoinPoint jp = mock(JoinPoint.class);
        Signature sig = mock(Signature.class);
        when(jp.getSignature()).thenReturn(sig);

        assertThatThrownBy(() -> aspect.enforceTenantContext(jp))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TenantContext not initialized");
    }

    @Test
    void shouldPass_whenContextPresent() {
        TenantContext.set(UUID.randomUUID(), UUID.randomUUID(), List.of("ROLE_CUSTOMER"));
        JoinPoint jp = mock(JoinPoint.class);

        assertThatCode(() -> aspect.enforceTenantContext(jp)).doesNotThrowAnyException();
    }
}
