package stirling.software.SPDF.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.Getter;

import stirling.software.common.model.ApplicationProperties;

import tools.jackson.databind.ObjectMapper;

@Service
public class KmsSignatureService {

    /**
     * Remote signer contract: POST JSON with keyId, algorithm, digestAlgorithm, and base64 digest;
     * return JSON with a base64 signature. The bridge is responsible for mapping the algorithm to
     * its KMS/HSM provider and signing the digest with the configured key.
     */
    private final ApplicationProperties applicationProperties;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public KmsSignatureService(
            ApplicationProperties applicationProperties, ObjectMapper objectMapper) {
        this.applicationProperties = applicationProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isEnabled() {
        var kms = applicationProperties.getSecurity().getSigning().getKms();
        return kms.isEnabled() && StringUtils.hasText(kms.getSignerUrl());
    }

    public byte[] signDigest(KmsSigningRequest request) throws IOException, InterruptedException {
        var kms = applicationProperties.getSecurity().getSigning().getKms();
        if (!isEnabled()) {
            throw new IOException("KMS signing is not enabled");
        }
        URI signerUri;
        try {
            signerUri = URI.create(kms.getSignerUrl());
        } catch (IllegalArgumentException e) {
            throw new IOException("KMS signer URL is invalid", e);
        }

        RemoteKmsSignRequest remoteRequest =
                new RemoteKmsSignRequest(
                        request.keyId(),
                        request.algorithm().getRemoteName(),
                        request.algorithm().getDigestAlgorithm(),
                        Base64.getEncoder().encodeToString(request.digest()));

        HttpRequest.Builder httpRequestBuilder =
                HttpRequest.newBuilder(signerUri)
                        .timeout(Duration.ofSeconds(Math.max(1, kms.getTimeoutSeconds())))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        objectMapper.writeValueAsString(remoteRequest)));

        if (StringUtils.hasText(kms.getAuthorizationHeader())) {
            httpRequestBuilder.header("Authorization", kms.getAuthorizationHeader());
        }

        HttpResponse<String> response =
                httpClient.send(httpRequestBuilder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                    "KMS signer returned HTTP " + response.statusCode() + " while signing digest");
        }

        RemoteKmsSignResponse signResponse =
                objectMapper.readValue(response.body(), RemoteKmsSignResponse.class);
        if (signResponse == null || !StringUtils.hasText(signResponse.signature())) {
            throw new IOException("KMS signer response did not contain a signature");
        }
        try {
            return Base64.getDecoder().decode(signResponse.signature());
        } catch (IllegalArgumentException e) {
            throw new IOException("KMS signer response signature was not valid base64", e);
        }
    }

    public KmsSignatureAlgorithm resolveAlgorithm(String requestAlgorithm) {
        var kms = applicationProperties.getSecurity().getSigning().getKms();
        String configuredAlgorithm =
                StringUtils.hasText(requestAlgorithm)
                        ? requestAlgorithm
                        : kms.getSignatureAlgorithm();
        return KmsSignatureAlgorithm.from(configuredAlgorithm);
    }

    public record KmsSigningRequest(String keyId, KmsSignatureAlgorithm algorithm, byte[] digest) {}

    private record RemoteKmsSignRequest(
            String keyId, String algorithm, String digestAlgorithm, String digest) {}

    private record RemoteKmsSignResponse(String signature) {}

    @Getter
    public enum KmsSignatureAlgorithm {
        SHA256_WITH_RSA("SHA256withRSA", "RSA", "SHA-256", "SHA256_WITH_RSA"),
        SHA256_WITH_ECDSA("SHA256withECDSA", "EC", "SHA-256", "SHA256_WITH_ECDSA");

        private final String cmsAlgorithmName;
        private final String certificateKeyAlgorithm;
        private final String digestAlgorithm;
        private final String remoteName;

        KmsSignatureAlgorithm(
                String cmsAlgorithmName,
                String certificateKeyAlgorithm,
                String digestAlgorithm,
                String remoteName) {
            this.cmsAlgorithmName = cmsAlgorithmName;
            this.certificateKeyAlgorithm = certificateKeyAlgorithm;
            this.digestAlgorithm = digestAlgorithm;
            this.remoteName = remoteName;
        }

        public static KmsSignatureAlgorithm from(String value) {
            if (!StringUtils.hasText(value)) {
                return SHA256_WITH_RSA;
            }
            String normalized = value.trim().replace('-', '_').replace(' ', '_').toUpperCase();
            return switch (normalized) {
                case "SHA256_WITH_RSA", "SHA256WITHRSA", "RSA" -> SHA256_WITH_RSA;
                case "SHA256_WITH_ECDSA", "SHA256WITHECDSA", "ECDSA", "EC" -> SHA256_WITH_ECDSA;
                default ->
                        throw new IllegalArgumentException(
                                "Unsupported KMS signature algorithm: " + value);
            };
        }
    }
}
