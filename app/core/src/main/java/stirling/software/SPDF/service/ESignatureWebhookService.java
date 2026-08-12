package stirling.software.SPDF.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import stirling.software.common.service.SsrfProtectionService;

@Service
public class ESignatureWebhookService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final SsrfProtectionService ssrfProtectionService;
    private final WebhookTransport transport;

    public ESignatureWebhookService(SsrfProtectionService ssrfProtectionService) {
        this(
                ssrfProtectionService,
                (uri, eventId, payload) -> {
                    HttpClient client =
                            HttpClient.newBuilder()
                                    .connectTimeout(CONNECT_TIMEOUT)
                                    .followRedirects(HttpClient.Redirect.NEVER)
                                    .build();
                    HttpRequest request =
                            HttpRequest.newBuilder(uri)
                                    .timeout(REQUEST_TIMEOUT)
                                    .header("Content-Type", "application/json")
                                    .header("User-Agent", "Stirling-PDF-Signing-Webhook/1.0")
                                    .header("X-Stirling-Event-Id", eventId)
                                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                                    .build();
                    HttpResponse<Void> response =
                            client.send(request, HttpResponse.BodyHandlers.discarding());
                    return response.statusCode();
                });
    }

    ESignatureWebhookService(
            SsrfProtectionService ssrfProtectionService, WebhookTransport transport) {
        this.ssrfProtectionService = ssrfProtectionService;
        this.transport = transport;
    }

    public DeliveryResult deliver(String callbackUrl, String eventId, String payload) {
        if (!StringUtils.hasText(callbackUrl)) {
            return DeliveryResult.failure(null, "Callback URL is not configured");
        }
        URI uri;
        try {
            uri = URI.create(callbackUrl.trim());
        } catch (IllegalArgumentException e) {
            return DeliveryResult.failure(null, "Callback URL is invalid");
        }
        if (!("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()))) {
            return DeliveryResult.failure(null, "Callback URL must use HTTP or HTTPS");
        }
        if (!ssrfProtectionService.isUrlAllowed(uri.toString())) {
            return DeliveryResult.failure(null, "Callback URL is blocked by the SSRF policy");
        }
        try {
            int statusCode = transport.send(uri, eventId, payload);
            if (statusCode >= 200 && statusCode < 300) {
                return DeliveryResult.success(statusCode);
            }
            return DeliveryResult.failure(statusCode, "Callback returned HTTP " + statusCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryResult.failure(null, "Callback delivery was interrupted");
        } catch (IOException | RuntimeException e) {
            return DeliveryResult.failure(null, "Callback delivery failed: " + e.getMessage());
        }
    }

    @FunctionalInterface
    interface WebhookTransport {
        int send(URI uri, String eventId, String payload) throws IOException, InterruptedException;
    }

    public record DeliveryResult(boolean delivered, Integer statusCode, String error) {
        static DeliveryResult success(int statusCode) {
            return new DeliveryResult(true, statusCode, null);
        }

        static DeliveryResult failure(Integer statusCode, String error) {
            return new DeliveryResult(false, statusCode, error);
        }
    }
}
