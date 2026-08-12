package stirling.software.SPDF.controller.api.security;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.service.CertificateValidationService;
import stirling.software.SPDF.service.SigningTrustStoreService;
import stirling.software.SPDF.service.SigningTrustStoreService.TrustCertificate;

@RestController
@RequestMapping("/api/v1/admin/signing-trust")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Signing Trust", description = "Manage PDF signature trust certificates")
public class SigningTrustStoreController {

    private final SigningTrustStoreService trustStoreService;
    private final CertificateValidationService validationService;

    @GetMapping
    @Operation(summary = "List managed PDF signature trust certificates")
    public List<TrustCertificate> listCertificates() throws IOException {
        return trustStoreService.listCertificates();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import an X.509 PDF signature trust certificate")
    public TrustCertificate importCertificate(@RequestParam("file") MultipartFile file)
            throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Certificate file is required");
        }
        TrustCertificate certificate = trustStoreService.importCertificate(file.getBytes());
        validationService.reloadManagedTrustCertificates();
        return certificate;
    }

    @GetMapping("/{fingerprint}")
    @Operation(summary = "Export a managed trust certificate")
    public ResponseEntity<byte[]> exportCertificate(@PathVariable String fingerprint)
            throws IOException {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fingerprint + ".cer\"")
                .contentType(MediaType.valueOf("application/pkix-cert"))
                .body(trustStoreService.exportCertificate(fingerprint));
    }

    @DeleteMapping("/{fingerprint}")
    @Operation(summary = "Delete a managed trust certificate")
    public ResponseEntity<Void> deleteCertificate(@PathVariable String fingerprint)
            throws Exception {
        trustStoreService.deleteCertificate(fingerprint);
        validationService.reloadManagedTrustCertificates();
        return ResponseEntity.noContent().build();
    }
}
