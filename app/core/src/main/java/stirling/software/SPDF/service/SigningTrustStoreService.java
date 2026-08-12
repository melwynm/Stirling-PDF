package stirling.software.SPDF.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import stirling.software.common.configuration.InstallationPathConfig;

@Service
public class SigningTrustStoreService {

    private static final long MAX_CERTIFICATE_BYTES = 2 * 1024 * 1024;
    private final Path trustDirectory;

    public SigningTrustStoreService() {
        this(Path.of(InstallationPathConfig.getConfigPath(), "signing-trust"));
    }

    SigningTrustStoreService(Path trustDirectory) {
        this.trustDirectory = trustDirectory.toAbsolutePath().normalize();
    }

    public synchronized TrustCertificate importCertificate(byte[] certificateBytes)
            throws IOException {
        if (certificateBytes == null
                || certificateBytes.length == 0
                || certificateBytes.length > MAX_CERTIFICATE_BYTES) {
            throw new IOException("Certificate must be between 1 byte and 2 MB");
        }
        X509Certificate certificate = parseCertificate(certificateBytes);
        byte[] encoded;
        try {
            certificate.checkValidity();
            encoded = certificate.getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IOException("Certificate is not currently valid", e);
        }
        String fingerprint = fingerprint(encoded);
        Files.createDirectories(trustDirectory);
        Path destination = certificatePath(fingerprint);
        Path temporary = Files.createTempFile(trustDirectory, fingerprint, ".tmp");
        try {
            Files.write(temporary, encoded);
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return describe(certificate, fingerprint, destination);
    }

    public synchronized List<TrustCertificate> listCertificates() throws IOException {
        if (!Files.isDirectory(trustDirectory)) {
            return List.of();
        }
        List<TrustCertificate> certificates = new ArrayList<>();
        try (var paths = Files.list(trustDirectory)) {
            for (Path path : paths.filter(this::isCertificateFile).toList()) {
                try {
                    X509Certificate certificate = parseCertificate(Files.readAllBytes(path));
                    certificates.add(
                            describe(
                                    certificate,
                                    path.getFileName().toString().replaceFirst("\\.cer$", ""),
                                    path));
                } catch (IOException e) {
                    // A malformed file is ignored rather than breaking the whole trust view.
                }
            }
        }
        return certificates.stream()
                .sorted(Comparator.comparing(TrustCertificate::subject))
                .toList();
    }

    public synchronized byte[] exportCertificate(String fingerprint) throws IOException {
        return Files.readAllBytes(requireCertificatePath(fingerprint));
    }

    public synchronized void deleteCertificate(String fingerprint) throws IOException {
        if (!Files.deleteIfExists(requireCertificatePath(fingerprint))) {
            throw new IOException("Trust certificate does not exist");
        }
    }

    public synchronized List<X509Certificate> loadCertificates() throws IOException {
        List<X509Certificate> certificates = new ArrayList<>();
        if (!Files.isDirectory(trustDirectory)) {
            return certificates;
        }
        try (var paths = Files.list(trustDirectory)) {
            for (Path path : paths.filter(this::isCertificateFile).toList()) {
                certificates.add(parseCertificate(Files.readAllBytes(path)));
            }
        }
        return certificates;
    }

    private Path requireCertificatePath(String fingerprint) throws IOException {
        String normalized = normalizeFingerprint(fingerprint);
        Path path = certificatePath(normalized);
        if (!Files.isRegularFile(path)) {
            throw new IOException("Trust certificate does not exist");
        }
        return path;
    }

    private Path certificatePath(String fingerprint) {
        return trustDirectory.resolve(fingerprint + ".cer").normalize();
    }

    private String normalizeFingerprint(String fingerprint) throws IOException {
        String normalized =
                fingerprint == null
                        ? ""
                        : fingerprint.replace(":", "").trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[0-9A-F]{64}")) {
            throw new IOException("Certificate fingerprint is invalid");
        }
        return normalized;
    }

    private boolean isCertificateFile(Path path) {
        return Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cer");
    }

    private X509Certificate parseCertificate(byte[] bytes) throws IOException {
        try {
            return (X509Certificate)
                    CertificateFactory.getInstance("X.509")
                            .generateCertificate(new ByteArrayInputStream(bytes));
        } catch (GeneralSecurityException e) {
            throw new IOException("Uploaded file is not an X.509 certificate", e);
        }
    }

    private String fingerprint(byte[] encoded) throws IOException {
        try {
            return HexFormat.of()
                    .withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (GeneralSecurityException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private TrustCertificate describe(
            X509Certificate certificate, String fingerprint, Path certificatePath)
            throws IOException {
        return new TrustCertificate(
                fingerprint,
                certificate.getSubjectX500Principal().getName(),
                certificate.getIssuerX500Principal().getName(),
                certificate.getSerialNumber().toString(16).toUpperCase(Locale.ROOT),
                certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant(),
                certificate.getBasicConstraints() >= 0,
                Files.getLastModifiedTime(certificatePath).toInstant());
    }

    public record TrustCertificate(
            String fingerprint,
            String subject,
            String issuer,
            String serialNumber,
            Instant validFrom,
            Instant validUntil,
            boolean certificateAuthority,
            Instant importedAt) {}
}
