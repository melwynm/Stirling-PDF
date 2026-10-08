package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import stirling.software.common.service.SsrfProtectionService;

class ESignatureWebhookServiceTest {

    @Test
    void rejectsUnsupportedAndSsrfBlockedCallbackUrls() {
        SsrfProtectionService ssrf = mock(SsrfProtectionService.class);
        ESignatureWebhookService service =
                new ESignatureWebhookService(ssrf, (uri, headers, payload) -> 204);

        assertFalse(service.deliver("file:///tmp/hook", "event-1", "{}").delivered());
        when(ssrf.isUrlAllowed("https://internal.example/hook")).thenReturn(false);
        assertFalse(service.deliver("https://internal.example/hook", "event-1", "{}").delivered());
    }

    @Test
    void sendsStableEventIdAndAcceptsOnlySuccessfulResponses() {
        SsrfProtectionService ssrf = mock(SsrfProtectionService.class);
        when(ssrf.isUrlAllowed("https://hooks.example/signing")).thenReturn(true);
        AtomicReference<String> receivedEventId = new AtomicReference<>();
        ESignatureWebhookService service =
                new ESignatureWebhookService(
                        ssrf,
                        (uri, headers, payload) -> {
                            receivedEventId.set(
                                    headers.get(ESignatureWebhookService.EVENT_ID_HEADER));
                            return 202;
                        });

        ESignatureWebhookService.DeliveryResult result =
                service.deliver(
                        "https://hooks.example/signing",
                        "event-123",
                        "{\"type\":\"REQUEST_SENT\"}");

        assertTrue(result.delivered());
        assertEquals(202, result.statusCode());
        assertEquals("event-123", receivedEventId.get());
    }

    @Test
    void signsTimestampAndPayloadWhenASecretIsConfigured() {
        SsrfProtectionService ssrf = mock(SsrfProtectionService.class);
        when(ssrf.isUrlAllowed("https://hooks.example/signing")).thenReturn(true);
        AtomicReference<Map<String, String>> received = new AtomicReference<>();
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
        ESignatureWebhookService service =
                new ESignatureWebhookService(
                        ssrf,
                        (uri, headers, payload) -> {
                            received.set(headers);
                            return 204;
                        },
                        () -> "shared-secret",
                        clock);

        assertTrue(
                service.deliver(
                                "https://hooks.example/signing",
                                "event-9",
                                "{\"type\":\"RECIPIENT_SIGNED\"}")
                        .delivered());

        Map<String, String> headers = received.get();
        assertEquals("event-9", headers.get(ESignatureWebhookService.EVENT_ID_HEADER));
        assertEquals("1791460800", headers.get(ESignatureWebhookService.TIMESTAMP_HEADER));
        // Expected value computed independently: HMAC-SHA256("shared-secret",
        // "1791460800.{\"type\":\"RECIPIENT_SIGNED\"}") with Python and OpenSSL.
        assertEquals(
                "sha256=e7668a59865c02aaa6cdc2ddace3836f7b14aeee4b9fffb6e2b6e3a21e77a708",
                headers.get(ESignatureWebhookService.SIGNATURE_HEADER));
    }

    @Test
    void sendsNoSignatureHeadersWithoutASecret() {
        SsrfProtectionService ssrf = mock(SsrfProtectionService.class);
        when(ssrf.isUrlAllowed("https://hooks.example/signing")).thenReturn(true);
        AtomicReference<Map<String, String>> received = new AtomicReference<>();
        ESignatureWebhookService service =
                new ESignatureWebhookService(
                        ssrf,
                        (uri, headers, payload) -> {
                            received.set(headers);
                            return 204;
                        },
                        () -> "  ",
                        Clock.systemUTC());

        service.deliver("https://hooks.example/signing", "event-10", "{}");

        assertFalse(received.get().containsKey(ESignatureWebhookService.SIGNATURE_HEADER));
        assertFalse(received.get().containsKey(ESignatureWebhookService.TIMESTAMP_HEADER));
    }
}
