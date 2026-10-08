package stirling.software.SPDF.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.service.SsrfProtectionService;

@Service
public class ESignatureWebhookService {

    static final String EVENT_ID_HEADER = "X-Stirling-Event-Id";
    static final String TIMESTAMP_HEADER = "X-Stirling-Timestamp";
    static final String SIGNATURE_HEADER = "X-Stirling-Signature";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final SsrfProtectionService ssrfProtectionService;
    private final WebhookTransport transport;
    private final Supplier<String> signingSecret;
    private final Clock clock;

    // Required: with additional test constructors Spring otherwise looks for a no-arg constructor
    // and the application fails to start.
    @Autowired
    public ESignatureWebhookService(
            SsrfProtectionService ssrfProtectionService,
            ApplicationProperties applicationProperties) {
        this(
                ssrfProtectionService,
                ESignatureWebhookService::sendHttp,
                () -> applicationProperties.getSigning().getWebhooks().getSigningSecret(),
                Clock.systemUTC());
    }

    ESignatureWebhookService(
            SsrfProtectionService ssrfProtectionService, WebhookTransport transport) {
        this(ssrfProtectionService, transport, () -> null, Clock.systemUTC());
    }

    ESignatureWebhookService(
            SsrfProtectionService ssrfProtectionService,
            WebhookTransport transport,
            Supplier<String> signingSecret,
            Clock clock) {
        this.ssrfProtectionService = ssrfProtectionService;
        this.transport = transport;
        this.signingSecret = signingSecret;
        this.clock = clock;
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
            int statusCode = transport.send(uri, headers(eventId, payload), payload);
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

    /**
     * With a signing secret configured, receivers verify {@code X-Stirling-Signature} as {@code
     * sha256=<hex HMAC-SHA256(secret, timestamp + "." + body)>} and reject stale timestamps to
     * prevent replay. The timestamp is in Unix seconds.
     */
    private Map<String, String> headers(String eventId, String payload) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(EVENT_ID_HEADER, eventId);
        String secret = signingSecret.get();
        if (StringUtils.hasText(secret)) {
            String timestamp = String.valueOf(clock.instant().getEpochSecond());
            headers.put(TIMESTAMP_HEADER, timestamp);
            headers.put(
                    SIGNATURE_HEADER, "sha256=" + hmacSha256(secret, timestamp + "." + payload));
        }
        return headers;
    }

    static String hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static int sendHttp(URI uri, Map<String, String> headers, String payload)
            throws IOException, InterruptedException {
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        HttpRequest.Builder request =
                HttpRequest.newBuilder(uri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Stirling-PDF-Signing-Webhook/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(payload));
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @FunctionalInterface
    interface WebhookTransport {
        int send(URI uri, Map<String, String> headers, String payload)
                throws IOException, InterruptedException;
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
