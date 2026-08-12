package stirling.software.SPDF.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.DigestInfo;
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
        return isEnabled(SignerProvider.KMS);
    }

    public boolean isEnabled(SignerProvider provider) {
        if (provider == SignerProvider.PKCS11) {
            var pkcs11 = applicationProperties.getSecurity().getSigning().getPkcs11();
            return pkcs11.isEnabled() && StringUtils.hasText(pkcs11.getLibrary());
        }
        RemoteSignerSettings settings = remoteSettings(provider);
        return settings.enabled() && StringUtils.hasText(settings.signerUrl());
    }

    public byte[] signDigest(KmsSigningRequest request) throws IOException, InterruptedException {
        return signDigest(SignerProvider.KMS, request);
    }

    public byte[] signDigest(SignerProvider provider, KmsSigningRequest request)
            throws IOException, InterruptedException {
        if (provider == SignerProvider.PKCS11) {
            return signWithPkcs11(request);
        }
        RemoteSignerSettings settings = remoteSettings(provider);
        if (!isEnabled(provider)) {
            throw new IOException(provider.getLabel() + " signing is not enabled");
        }
        URI signerUri;
        try {
            signerUri = URI.create(settings.signerUrl());
        } catch (IllegalArgumentException e) {
            throw new IOException(provider.getLabel() + " signer URL is invalid", e);
        }

        RemoteKmsSignRequest remoteRequest =
                new RemoteKmsSignRequest(
                        request.keyId(),
                        provider.name(),
                        request.algorithm().getRemoteName(),
                        request.algorithm().getDigestAlgorithm(),
                        Base64.getEncoder().encodeToString(request.digest()));

        HttpRequest.Builder httpRequestBuilder =
                HttpRequest.newBuilder(signerUri)
                        .timeout(Duration.ofSeconds(Math.max(1, settings.timeoutSeconds())))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        objectMapper.writeValueAsString(remoteRequest)));

        if (StringUtils.hasText(settings.authorizationHeader())) {
            httpRequestBuilder.header("Authorization", settings.authorizationHeader());
        }

        HttpResponse<String> response =
                httpClient.send(httpRequestBuilder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                    provider.getLabel()
                            + " signer returned HTTP "
                            + response.statusCode()
                            + " while signing digest");
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
        return resolveAlgorithm(SignerProvider.KMS, requestAlgorithm);
    }

    public KmsSignatureAlgorithm resolveAlgorithm(
            SignerProvider provider, String requestAlgorithm) {
        String providerAlgorithm =
                provider == SignerProvider.PKCS11
                        ? applicationProperties
                                .getSecurity()
                                .getSigning()
                                .getPkcs11()
                                .getSignatureAlgorithm()
                        : remoteSettings(provider).signatureAlgorithm();
        String configuredAlgorithm =
                StringUtils.hasText(requestAlgorithm) ? requestAlgorithm : providerAlgorithm;
        return KmsSignatureAlgorithm.from(configuredAlgorithm);
    }

    private byte[] signWithPkcs11(KmsSigningRequest request) throws IOException {
        var settings = applicationProperties.getSecurity().getSigning().getPkcs11();
        if (!isEnabled(SignerProvider.PKCS11)) {
            throw new IOException("PKCS#11 signing is not enabled");
        }
        try {
            java.nio.file.Path libraryPath = java.nio.file.Path.of(settings.getLibrary());
            if (!libraryPath.isAbsolute()
                    || settings.getLibrary().contains("\n")
                    || settings.getLibrary().contains("\r")) {
                throw new IOException("PKCS#11 library must be an absolute path");
            }
            String providerName = "StirlingPkcs11";
            StringBuilder config =
                    new StringBuilder()
                            .append("name = ")
                            .append(providerName)
                            .append(System.lineSeparator())
                            .append("library = ")
                            .append(libraryPath.normalize())
                            .append(System.lineSeparator());
            if (StringUtils.hasText(settings.getSlot())) {
                config.append("slot = ")
                        .append(Long.parseLong(settings.getSlot().trim()))
                        .append(System.lineSeparator());
            }
            java.security.Provider base = java.security.Security.getProvider("SunPKCS11");
            if (base == null) {
                throw new IOException("SunPKCS11 provider is unavailable in this Java runtime");
            }
            java.nio.file.Path configFile =
                    java.nio.file.Files.createTempFile("spdf-pkcs11-", ".cfg");
            try {
                java.nio.file.Files.writeString(configFile, config.toString());
                java.security.Provider provider = base.configure(configFile.toString());
                java.security.Security.addProvider(provider);
                java.security.KeyStore keyStore =
                        java.security.KeyStore.getInstance("PKCS11", provider);
                keyStore.load(null, settings.getPin().toCharArray());
                String alias =
                        StringUtils.hasText(request.keyId())
                                ? request.keyId()
                                : keyStore.aliases().nextElement();
                java.security.PrivateKey key =
                        (java.security.PrivateKey)
                                keyStore.getKey(alias, settings.getPin().toCharArray());
                boolean rsa = request.algorithm() == KmsSignatureAlgorithm.SHA256_WITH_RSA;
                java.security.Signature signature =
                        java.security.Signature.getInstance(
                                rsa ? "NONEwithRSA" : "NONEwithECDSA", provider);
                signature.initSign(key);
                signature.update(
                        rsa
                                ? new DigestInfo(
                                                new AlgorithmIdentifier(
                                                        NISTObjectIdentifiers.id_sha256,
                                                        DERNull.INSTANCE),
                                                request.digest())
                                        .getEncoded()
                                : request.digest());
                return signature.sign();
            } finally {
                java.nio.file.Files.deleteIfExists(configFile);
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IOException("PKCS#11 signing failed", e);
        }
    }

    private RemoteSignerSettings remoteSettings(SignerProvider provider) {
        var signing = applicationProperties.getSecurity().getSigning();
        return switch (provider) {
            case KMS ->
                    new RemoteSignerSettings(
                            signing.getKms().isEnabled(),
                            signing.getKms().getSignerUrl(),
                            signing.getKms().getAuthorizationHeader(),
                            signing.getKms().getSignatureAlgorithm(),
                            signing.getKms().getTimeoutSeconds());
            case REMOTE -> from(signing.getRemote());
            case CLOUD_KMS -> from(signing.getCloudKms());
            case QES -> from(signing.getQes());
            case PKCS11 -> throw new IllegalArgumentException("PKCS#11 is not a remote signer");
        };
    }

    private RemoteSignerSettings from(
            ApplicationProperties.Security.Signing.RemoteSigner settings) {
        return new RemoteSignerSettings(
                settings.isEnabled(),
                settings.getSignerUrl(),
                settings.getAuthorizationHeader(),
                settings.getSignatureAlgorithm(),
                settings.getTimeoutSeconds());
    }

    public record KmsSigningRequest(String keyId, KmsSignatureAlgorithm algorithm, byte[] digest) {}

    private record RemoteKmsSignRequest(
            String keyId,
            String provider,
            String algorithm,
            String digestAlgorithm,
            String digest) {}

    private record RemoteKmsSignResponse(String signature) {}

    private record RemoteSignerSettings(
            boolean enabled,
            String signerUrl,
            String authorizationHeader,
            String signatureAlgorithm,
            int timeoutSeconds) {}

    @Getter
    public enum SignerProvider {
        KMS("KMS"),
        REMOTE("Remote"),
        CLOUD_KMS("Cloud KMS"),
        QES("QES"),
        PKCS11("PKCS#11");

        private final String label;

        SignerProvider(String label) {
            this.label = label;
        }

        public static SignerProvider from(String value) {
            if (!StringUtils.hasText(value)) {
                return KMS;
            }
            return SignerProvider.valueOf(
                    value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT));
        }
    }

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
