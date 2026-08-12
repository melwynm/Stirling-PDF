package stirling.software.SPDF.controller.api.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import stirling.software.SPDF.service.CertificateValidationService;
import stirling.software.SPDF.service.SigningTrustStoreService;
import stirling.software.SPDF.service.SigningTrustStoreService.TrustCertificate;

@ExtendWith(MockitoExtension.class)
class SigningTrustStoreControllerTest {

    @Mock private SigningTrustStoreService trustStoreService;
    @Mock private CertificateValidationService validationService;

    private SigningTrustStoreController controller;
    private TrustCertificate certificate;

    @BeforeEach
    void setUp() {
        controller = new SigningTrustStoreController(trustStoreService, validationService);
        certificate =
                new TrustCertificate(
                        "A".repeat(64),
                        "CN=Signer",
                        "CN=Issuer",
                        "1",
                        Instant.now(),
                        Instant.now().plusSeconds(3600),
                        true,
                        Instant.now());
    }

    @Test
    void listsManagedCertificates() throws Exception {
        when(trustStoreService.listCertificates()).thenReturn(List.of(certificate));

        assertEquals(List.of(certificate), controller.listCertificates());
    }

    @Test
    void importsAndReloadsTrustAnchors() throws Exception {
        byte[] bytes = new byte[] {1, 2, 3};
        when(trustStoreService.importCertificate(bytes)).thenReturn(certificate);

        TrustCertificate result =
                controller.importCertificate(
                        new MockMultipartFile("file", "trust.cer", "application/pkix-cert", bytes));

        assertEquals(certificate, result);
        verify(validationService).reloadManagedTrustCertificates();
    }

    @Test
    void exportsAndDeletesCertificates() throws Exception {
        byte[] bytes = new byte[] {4, 5, 6};
        when(trustStoreService.exportCertificate(certificate.fingerprint())).thenReturn(bytes);

        ResponseEntity<byte[]> exported = controller.exportCertificate(certificate.fingerprint());
        assertArrayEquals(bytes, exported.getBody());

        assertEquals(
                204,
                controller.deleteCertificate(certificate.fingerprint()).getStatusCode().value());
        verify(trustStoreService).deleteCertificate(certificate.fingerprint());
        verify(validationService).reloadManagedTrustCertificates();
    }
}
