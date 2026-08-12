package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import stirling.software.SPDF.service.KmsSignatureService.KmsSignatureAlgorithm;
import stirling.software.SPDF.service.KmsSignatureService.KmsSigningRequest;
import stirling.software.common.model.ApplicationProperties;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import tools.jackson.databind.json.JsonMapper;

class KmsSignatureServiceTest {

    @Test
    void signDigestSendsConfiguredContractAndReturnsDecodedSignature() throws Exception {
        byte[] digest = {1, 2, 3, 4};
        byte[] signature = {5, 6, 7, 8};

        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(
                    new MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody(
                                    "{\"signature\":\""
                                            + Base64.getEncoder().encodeToString(signature)
                                            + "\"}"));
            server.start();

            KmsSignatureService service = service(server, "Bearer test-token");
            byte[] actual =
                    service.signDigest(
                            new KmsSigningRequest(
                                    "alias/pdf-signing",
                                    KmsSignatureAlgorithm.SHA256_WITH_RSA,
                                    digest));

            assertArrayEquals(signature, actual);
            RecordedRequest request = server.takeRequest();
            assertEquals("/sign", request.getPath());
            assertEquals("POST", request.getMethod());
            assertEquals("Bearer test-token", request.getHeader("Authorization"));
            assertEquals("application/json", request.getHeader("Content-Type"));
            String requestBody = request.getBody().readUtf8();
            assertTrue(requestBody.contains("\"keyId\":\"alias/pdf-signing\""));
            assertTrue(requestBody.contains("\"algorithm\":\"SHA256_WITH_RSA\""));
            assertTrue(requestBody.contains("\"digestAlgorithm\":\"SHA-256\""));
            assertTrue(
                    requestBody.contains(
                            "\"digest\":\"" + Base64.getEncoder().encodeToString(digest) + "\""));
        }
    }

    @Test
    void signDigestRejectsBridgeErrorsAndMalformedSignatures() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(502));
            server.enqueue(
                    new MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody("{\"signature\":\"not-base64!\"}"));
            server.start();

            KmsSignatureService service = service(server, "");
            KmsSigningRequest request =
                    new KmsSigningRequest(
                            "key", KmsSignatureAlgorithm.SHA256_WITH_RSA, new byte[] {1});

            IOException bridgeError =
                    assertThrows(IOException.class, () -> service.signDigest(request));
            assertTrue(bridgeError.getMessage().contains("HTTP 502"));

            IOException malformedSignature =
                    assertThrows(IOException.class, () -> service.signDigest(request));
            assertTrue(malformedSignature.getMessage().contains("not valid base64"));
        }
    }

    @Test
    void disabledConfigurationAndAlgorithmFallbackAreExplicit() {
        ApplicationProperties properties = new ApplicationProperties();
        var kms = properties.getSecurity().getSigning().getKms();
        kms.setEnabled(true);
        kms.setSignerUrl("");
        kms.setSignatureAlgorithm("SHA256_WITH_ECDSA");
        KmsSignatureService service =
                new KmsSignatureService(properties, JsonMapper.builder().build());

        assertFalse(service.isEnabled());
        assertEquals(KmsSignatureAlgorithm.SHA256_WITH_ECDSA, service.resolveAlgorithm(null));
        assertEquals(KmsSignatureAlgorithm.SHA256_WITH_RSA, service.resolveAlgorithm("rsa"));
        assertThrows(
                IllegalArgumentException.class, () -> service.resolveAlgorithm("SHA256_WITH_DSA"));
    }

    private KmsSignatureService service(MockWebServer server, String authorizationHeader) {
        ApplicationProperties properties = new ApplicationProperties();
        var kms = properties.getSecurity().getSigning().getKms();
        kms.setEnabled(true);
        kms.setSignerUrl(server.url("/sign").toString());
        kms.setAuthorizationHeader(authorizationHeader);
        kms.setTimeoutSeconds(5);
        return new KmsSignatureService(properties, JsonMapper.builder().build());
    }
}
