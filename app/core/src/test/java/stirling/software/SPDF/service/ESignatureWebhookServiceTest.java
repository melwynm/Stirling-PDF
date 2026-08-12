package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import stirling.software.common.service.SsrfProtectionService;

class ESignatureWebhookServiceTest {

    @Test
    void rejectsUnsupportedAndSsrfBlockedCallbackUrls() {
        SsrfProtectionService ssrf = mock(SsrfProtectionService.class);
        ESignatureWebhookService service =
                new ESignatureWebhookService(ssrf, (uri, eventId, payload) -> 204);

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
                        (uri, eventId, payload) -> {
                            receivedEventId.set(eventId);
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
}
