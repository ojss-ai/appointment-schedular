// TASK: P1-T08
package com.scheduler.api.notification;

import com.twilio.exception.ApiException;
import com.twilio.http.TwilioRestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DispatchServiceTest {

    @Mock
    private DispatchAdapter emailAdapter;

    @Mock
    private DispatchAdapter smsAdapter;

    private DispatchService dispatchService;

    @BeforeEach
    void setUp() {
        dispatchService = new DispatchService(List.of(emailAdapter, smsAdapter));
    }

    @Test
    void shouldRouteEmailIdentifier_toEmailAdapter() {
        when(emailAdapter.supports("email")).thenReturn(true);
        when(emailAdapter.sendOtp(anyString(), anyString(), anyString()))
            .thenReturn(DispatchResult.success("email"));

        DispatchResult result = dispatchService.dispatch(
            "user@example.com", "email", "A3K9PQ", "Acme");

        assertThat(result.success()).isTrue();
        verify(emailAdapter).sendOtp("user@example.com", "A3K9PQ", "Acme");
        verify(smsAdapter, never()).sendOtp(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRoutePhoneIdentifier_toSmsAdapter() {
        when(emailAdapter.supports("phone")).thenReturn(false);
        when(smsAdapter.supports("phone")).thenReturn(true);
        when(smsAdapter.sendOtp(anyString(), anyString(), anyString()))
            .thenReturn(DispatchResult.success("sms"));

        DispatchResult result = dispatchService.dispatch(
            "+15551234567", "phone", "A3K9PQ", "Acme");

        assertThat(result.success()).isTrue();
        verify(smsAdapter).sendOtp("+15551234567", "A3K9PQ", "Acme");
        verify(emailAdapter, never()).sendOtp(anyString(), anyString(), anyString());
    }

    @Test
    void shouldReturnFailure_whenNoAdapterFound() {
        when(emailAdapter.supports("fax")).thenReturn(false);
        when(smsAdapter.supports("fax")).thenReturn(false);

        DispatchResult result = dispatchService.dispatch(
            "555-0100", "fax", "A3K9PQ", "Acme");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("No adapter found");
    }

    @Test
    void shouldReturnFailure_whenSesThrows() {
        SesV2Client ses = mock(SesV2Client.class);
        when(ses.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(SesV2Exception.builder().message("ses down").build());
        EmailDispatchAdapter adapter = new EmailDispatchAdapter(
            ses, new DispatchProperties("no-reply@example.com", "+15005550006"));

        DispatchResult result = adapter.sendOtp("user@example.com", "A3K9PQ", "Acme");

        assertThat(result.success()).isFalse();
        assertThat(result.channel()).isEqualTo("email");
        assertThat(result.errorMessage()).contains("ses down");
    }

    @Test
    void shouldReturnFailure_whenTwilioThrows() {
        TwilioRestClient twilio = mock(TwilioRestClient.class);
        when(twilio.request(any())).thenThrow(new ApiException("twilio down"));
        SmsDispatchAdapter adapter = new SmsDispatchAdapter(
            twilio, new DispatchProperties("no-reply@example.com", "+15005550006"));

        DispatchResult result = adapter.sendOtp("+15551234567", "A3K9PQ", "Acme");

        assertThat(result.success()).isFalse();
        assertThat(result.channel()).isEqualTo("sms");
        assertThat(result.errorMessage()).contains("twilio down");
    }
}
