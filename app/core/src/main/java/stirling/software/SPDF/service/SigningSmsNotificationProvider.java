package stirling.software.SPDF.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.service.SigningNotificationProvider;
import stirling.software.common.service.SsrfProtectionService;

import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(
        value = "signing.notifications.sms.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class SigningSmsNotificationProvider implements SigningNotificationProvider {

    private final ApplicationProperties applicationProperties;
    private final SsrfProtectionService ssrfProtectionService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SigningSmsNotificationProvider(
            ApplicationProperties applicationProperties,
            SsrfProtectionService ssrfProtectionService,
            ObjectMapper objectMapper) {
        this.applicationProperties = applicationProperties;
        this.ssrfProtectionService = ssrfProtectionService;
        this.objectMapper = objectMapper;
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    @Override
    public String channel() {
        return "sms";
    }

    @Override
    public void send(SigningNotificationMessage message) throws Exception {
        ApplicationProperties.Signing.Notifications.Sms sms =
                applicationProperties.getSigning().getNotifications().getSms();
        if (!StringUtils.hasText(sms.getEndpoint())
                || !ssrfProtectionService.isUrlAllowed(sms.getEndpoint())) {
            throw new IllegalStateException(
                    "SMS endpoint is missing or blocked by the SSRF policy");
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("to", message.destination());
        payload.put("recipientName", message.recipientName());
        payload.put("subject", message.subject());
        payload.put("body", message.body());
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(sms.getEndpoint()))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Stirling-PDF-Signing-SMS/1.0")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        objectMapper.writeValueAsString(payload)));
        if (StringUtils.hasText(sms.getAuthorizationHeader())) {
            request.header("Authorization", sms.getAuthorizationHeader());
        }
        int status =
                httpClient
                        .send(request.build(), HttpResponse.BodyHandlers.discarding())
                        .statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("SMS gateway returned HTTP " + status);
        }
    }
}
