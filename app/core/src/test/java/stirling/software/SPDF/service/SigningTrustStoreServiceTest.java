package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class SigningTrustStoreServiceTest {

    @TempDir Path temporaryDirectory;

    @Test
    void importsListsExportsAndDeletesCertificates() throws Exception {
        SigningTrustStoreService service = new SigningTrustStoreService(temporaryDirectory);
        byte[] certificate = certificateBytes();

        SigningTrustStoreService.TrustCertificate imported = service.importCertificate(certificate);
        SigningTrustStoreService.TrustCertificate duplicate =
                service.importCertificate(certificate);

        assertEquals(imported.fingerprint(), duplicate.fingerprint());
        assertEquals(64, imported.fingerprint().length());
        assertEquals(1, service.listCertificates().size());
        assertEquals(1, service.loadCertificates().size());
        assertArrayEquals(certificate, service.exportCertificate(imported.fingerprint()));

        service.deleteCertificate(imported.fingerprint());
        assertTrue(service.listCertificates().isEmpty());
    }

    @Test
    void rejectsMalformedCertificatesAndFingerprints() {
        SigningTrustStoreService service = new SigningTrustStoreService(temporaryDirectory);

        assertThrows(IOException.class, () -> service.importCertificate(new byte[] {1, 2, 3}));
        assertThrows(IOException.class, () -> service.exportCertificate("../../settings.yml"));
        assertThrows(IOException.class, () -> service.deleteCertificate("not-a-fingerprint"));
    }

    private byte[] certificateBytes() throws Exception {
        try (InputStream input = new ClassPathResource("certs/test-cert.der").getInputStream()) {
            return input.readAllBytes();
        }
    }
}
