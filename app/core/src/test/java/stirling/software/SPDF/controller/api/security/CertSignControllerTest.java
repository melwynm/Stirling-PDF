package stirling.software.SPDF.controller.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.DigestInfo;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import stirling.software.SPDF.model.api.security.SignPDFWithCertRequest;
import stirling.software.SPDF.service.KmsSignatureService;
import stirling.software.SPDF.service.KmsSignatureService.KmsSignatureAlgorithm;
import stirling.software.SPDF.service.KmsSignatureService.KmsSigningRequest;
import stirling.software.SPDF.service.PadesLtvService;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.service.ServerCertificateServiceInterface;

@ExtendWith(MockitoExtension.class)
class CertSignControllerTest {

    @Mock private CustomPDFDocumentFactory pdfDocumentFactory;
    @Mock private ServerCertificateServiceInterface serverCertificateService;
    @Mock private KmsSignatureService kmsSignatureService;
    @Mock private PadesLtvService padesLtvService;

    @InjectMocks private CertSignController certSignController;

    private byte[] pdfBytes;
    private byte[] pfxBytes;
    private byte[] p12Bytes;
    private byte[] jksBytes;
    private byte[] pemKeyBytes;
    private byte[] pemCertBytes;
    private byte[] keyBytes;
    private byte[] crtCertBytes;
    private byte[] cerCertBytes;
    private byte[] derCertBytes;
    private PrivateKey kmsPrivateKey;
    private X509Certificate kmsCertificate;

    @BeforeEach
    void setUp() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            pdfBytes = baos.toByteArray();
        }
        ClassPathResource pfxResource = new ClassPathResource("certs/test-cert.pfx");
        try (InputStream is = pfxResource.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            is.transferTo(baos);
            pfxBytes = baos.toByteArray();
        }
        ClassPathResource p12Resource = new ClassPathResource("certs/test-cert.p12");
        try (InputStream is = p12Resource.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            is.transferTo(baos);
            p12Bytes = baos.toByteArray();
        }
        KeyStore kmsKeyStore = KeyStore.getInstance("PKCS12");
        kmsKeyStore.load(new ByteArrayInputStream(p12Bytes), "password".toCharArray());
        String kmsAlias = kmsKeyStore.aliases().nextElement();
        kmsPrivateKey = (PrivateKey) kmsKeyStore.getKey(kmsAlias, "password".toCharArray());
        kmsCertificate = (X509Certificate) kmsKeyStore.getCertificate(kmsAlias);
        ClassPathResource jksResource = new ClassPathResource("certs/test-cert.jks");
        try (InputStream is = jksResource.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            is.transferTo(baos);
            jksBytes = baos.toByteArray();
        }
        ClassPathResource pemKeyResource = new ClassPathResource("certs/test-key.pem");
        try (InputStream is = pemKeyResource.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            is.transferTo(baos);
            pemKeyBytes = baos.toByteArray();
        }
        ClassPathResource pemCertResource = new ClassPathResource("certs/test-cert.pem");
        try (InputStream is = pemCertResource.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            is.transferTo(baos);
            pemCertBytes = baos.toByteArray();
        }
        ClassPathResource keyResource = new ClassPathResource("certs/test-key.key");
        try (InputStream is = keyResource.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            is.transferTo(baos);
            keyBytes = baos.toByteArray();
        }
        ClassPathResource crtResource = new ClassPathResource("certs/test-cert.crt");
        try (InputStream is = crtResource.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            is.transferTo(baos);
            crtCertBytes = baos.toByteArray();
        }
        ClassPathResource cerResource = new ClassPathResource("certs/test-cert.cer");
        try (InputStream is = cerResource.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            is.transferTo(baos);
            cerCertBytes = baos.toByteArray();
        }
        ClassPathResource derCertResource = new ClassPathResource("certs/test-cert.der");
        try (InputStream is = derCertResource.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            is.transferTo(baos);
            derCertBytes = baos.toByteArray();
        }

        lenient()
                .when(pdfDocumentFactory.load(any(MultipartFile.class)))
                .thenAnswer(
                        invocation -> {
                            MultipartFile file = invocation.getArgument(0);
                            return Loader.loadPDF(file.getBytes());
                        });
    }

    @Test
    void testSignPdfWithPfx() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile pfxFile =
                new MockMultipartFile("p12File", "test-cert.pfx", "application/x-pkcs12", pfxBytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PFX");
        request.setP12File(pfxFile);
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<byte[]> response = certSignController.signPDFWithCert(request);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
    }

    @Test
    void testSignPdfWithPkcs12() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile p12File =
                new MockMultipartFile("p12File", "test-cert.p12", "application/x-pkcs12", p12Bytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PKCS12");
        request.setP12File(p12File);
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<byte[]> response = certSignController.signPDFWithCert(request);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
    }

    @Test
    void testLocalSignatureUsesPadesBaselineAttributes() throws Exception {
        ResponseEntity<byte[]> response = signWithPkcs12(pdfBytes, null);

        try (PDDocument signedDocument = Loader.loadPDF(response.getBody())) {
            PDSignature pdfSignature = signedDocument.getSignatureDictionaries().getFirst();
            assertEquals(
                    PDSignature.SUBFILTER_ETSI_CADES_DETACHED.getName(),
                    pdfSignature.getSubFilter());

            SignerInformation signerInformation = readSigner(response.getBody(), pdfSignature);
            assertTrue(
                    signerInformation.verify(
                            new JcaSimpleSignerInfoVerifierBuilder().build(kmsCertificate)));
            assertNotNull(
                    signerInformation
                            .getSignedAttributes()
                            .get(PKCSObjectIdentifiers.id_aa_signingCertificateV2));
        }
    }

    @Test
    void testTargetsExistingUnsignedSignatureField() throws Exception {
        byte[] fieldPdf;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(document);
            document.getDocumentCatalog().setAcroForm(acroForm);
            PDSignatureField field = new PDSignatureField(acroForm);
            field.setPartialName("approverSignature");
            field.getWidgets().getFirst().setRectangle(new PDRectangle(72, 72, 220, 60));
            field.getWidgets().getFirst().setPage(page);
            page.getAnnotations().add(field.getWidgets().getFirst());
            acroForm.getFields().add(field);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            fieldPdf = output.toByteArray();
        }

        ResponseEntity<byte[]> response = signWithPkcs12(fieldPdf, "approverSignature");

        try (PDDocument signedDocument = Loader.loadPDF(response.getBody())) {
            PDAcroForm acroForm = signedDocument.getDocumentCatalog().getAcroForm();
            assertEquals(1, acroForm.getFields().size());
            PDSignatureField field = (PDSignatureField) acroForm.getField("approverSignature");
            assertNotNull(field.getSignature());
            assertEquals(1, signedDocument.getSignatureDictionaries().size());
        }
    }

    @Test
    void testAddsMultipleSignaturesIncrementally() throws Exception {
        byte[] firstRevision = signWithPkcs12(pdfBytes, null).getBody();
        byte[] secondRevision = signWithPkcs12(firstRevision, null).getBody();

        try (PDDocument signedDocument = Loader.loadPDF(secondRevision)) {
            assertEquals(2, signedDocument.getSignatureDictionaries().size());
            for (PDSignature signature : signedDocument.getSignatureDictionaries()) {
                assertTrue(
                        readSigner(secondRevision, signature)
                                .verify(
                                        new JcaSimpleSignerInfoVerifierBuilder()
                                                .build(kmsCertificate)));
            }
        }
    }

    @Test
    void testPadesTimestampRequiresTsaUrl() {
        SignPDFWithCertRequest request = createPkcs12Request(pdfBytes);
        request.setPadesProfile("B_T");

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> certSignController.signPDFWithCert(request));

        assertTrue(exception.getMessage().contains("TSA URL is required"));
    }

    @Test
    void testLongTermPadesProfilesRequireTimestampAuthority() {
        SignPDFWithCertRequest request = createPkcs12Request(pdfBytes);
        request.setPadesProfile("B_LTA");

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> certSignController.signPDFWithCert(request));

        assertTrue(exception.getMessage().contains("TSA URL is required"));
    }

    @Test
    void testSignPdfWithMissingPkcs12FileThrowsError() {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PFX");
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> certSignController.signPDFWithCert(request));

        assertTrue(exception.getMessage().contains("PKCS12 keystore"));
    }

    @Test
    void testSignPdfWithJks() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile jksFile =
                new MockMultipartFile(
                        "jksFile", "test-cert.jks", "application/octet-stream", jksBytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("JKS");
        request.setJksFile(jksFile);
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<byte[]> response = certSignController.signPDFWithCert(request);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
    }

    @Test
    void testSignPdfWithPem() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile keyFile =
                new MockMultipartFile(
                        "privateKeyFile", "test-key.pem", "application/x-pem-file", pemKeyBytes);
        MockMultipartFile certFile =
                new MockMultipartFile(
                        "certFile", "test-cert.pem", "application/x-pem-file", pemCertBytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PEM");
        request.setPrivateKeyFile(keyFile);
        request.setCertFile(certFile);
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<byte[]> response = certSignController.signPDFWithCert(request);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
    }

    @Test
    void testSignPdfWithKmsUsesPadesSubfilter() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile certFile =
                new MockMultipartFile(
                        "certFile",
                        "test-cert.der",
                        "application/x-x509-ca-cert",
                        kmsCertificate.getEncoded());

        when(kmsSignatureService.isEnabled()).thenReturn(true);
        when(kmsSignatureService.resolveAlgorithm("SHA256_WITH_RSA"))
                .thenReturn(KmsSignatureAlgorithm.SHA256_WITH_RSA);
        when(kmsSignatureService.signDigest(any(KmsSigningRequest.class)))
                .thenAnswer(invocation -> signSha256RsaDigest(invocation.getArgument(0)));

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("KMS");
        request.setCertFile(certFile);
        request.setKmsKeyId("test-kms-key");
        request.setKmsSignatureAlgorithm("SHA256_WITH_RSA");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<byte[]> response = certSignController.signPDFWithCert(request);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
        try (PDDocument signedDocument = Loader.loadPDF(response.getBody())) {
            assertEquals(1, signedDocument.getSignatureDictionaries().size());
            PDSignature pdfSignature = signedDocument.getSignatureDictionaries().get(0);
            assertEquals(
                    PDSignature.SUBFILTER_ETSI_CADES_DETACHED.getName(),
                    pdfSignature.getSubFilter());

            byte[] signedContent =
                    pdfSignature.getSignedContent(new ByteArrayInputStream(response.getBody()));
            byte[] cmsBytes =
                    pdfSignature.getContents(new ByteArrayInputStream(response.getBody()));
            CMSSignedData cms =
                    new CMSSignedData(new CMSProcessableByteArray(signedContent), cmsBytes);
            SignerInformation signerInformation =
                    cms.getSignerInfos().getSigners().iterator().next();

            assertTrue(
                    signerInformation.verify(
                            new JcaSimpleSignerInfoVerifierBuilder().build(kmsCertificate)));
            assertNotNull(
                    signerInformation
                            .getSignedAttributes()
                            .get(PKCSObjectIdentifiers.id_aa_signingCertificateV2));
        }
    }

    private byte[] signSha256RsaDigest(KmsSigningRequest request) throws Exception {
        assertEquals(KmsSignatureAlgorithm.SHA256_WITH_RSA, request.algorithm());
        DigestInfo digestInfo =
                new DigestInfo(
                        new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE),
                        request.digest());
        Signature signature = Signature.getInstance("NONEwithRSA");
        signature.initSign(kmsPrivateKey);
        signature.update(digestInfo.getEncoded());
        return signature.sign();
    }

    private ResponseEntity<byte[]> signWithPkcs12(byte[] inputPdf, String signatureFieldName)
            throws Exception {
        SignPDFWithCertRequest request = createPkcs12Request(inputPdf);
        request.setSignatureFieldName(signatureFieldName);
        return certSignController.signPDFWithCert(request);
    }

    private SignPDFWithCertRequest createPkcs12Request(byte[] inputPdf) {
        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, inputPdf));
        request.setCertType("PKCS12");
        request.setP12File(
                new MockMultipartFile(
                        "p12File", "test-cert.p12", "application/x-pkcs12", p12Bytes));
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);
        return request;
    }

    private SignerInformation readSigner(byte[] signedPdf, PDSignature pdfSignature)
            throws Exception {
        byte[] signedContent = pdfSignature.getSignedContent(new ByteArrayInputStream(signedPdf));
        byte[] cmsBytes = pdfSignature.getContents(new ByteArrayInputStream(signedPdf));
        CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(signedContent), cmsBytes);
        return cms.getSignerInfos().getSigners().iterator().next();
    }

    @Test
    void testSignPdfWithKmsInvalidAlgorithmThrowsError() {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile certFile =
                new MockMultipartFile(
                        "certFile", "test-cert.pem", "application/x-pem-file", pemCertBytes);

        when(kmsSignatureService.isEnabled()).thenReturn(true);
        when(kmsSignatureService.resolveAlgorithm("SHA256_WITH_DSA"))
                .thenThrow(
                        new IllegalArgumentException(
                                "Unsupported KMS signature algorithm: SHA256_WITH_DSA"));

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("KMS");
        request.setCertFile(certFile);
        request.setKmsSignatureAlgorithm("SHA256_WITH_DSA");
        request.setShowSignature(false);

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> certSignController.signPDFWithCert(request));

        assertTrue(exception.getMessage().contains("Unsupported KMS signature algorithm"));
    }

    @Test
    void testVisibleSignatureRejectsMissingPage() {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile p12File =
                new MockMultipartFile("p12File", "test-cert.p12", "application/x-pkcs12", p12Bytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PKCS12");
        request.setP12File(p12File);
        request.setPassword("password");
        request.setShowSignature(true);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(2);
        request.setShowLogo(false);

        Exception exception =
                assertThrows(Exception.class, () -> certSignController.signPDFWithCert(request));

        assertTrue(
                exception
                        .getMessage()
                        .contains(
                                "Visible signature page number must reference an existing PDF page"));
    }

    @Test
    void testSignPdfWithCrt() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile keyFile =
                new MockMultipartFile(
                        "privateKeyFile", "test-key.key", "application/x-pem-file", keyBytes);
        MockMultipartFile certFile =
                new MockMultipartFile(
                        "certFile", "test-cert.crt", "application/x-x509-ca-cert", crtCertBytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PEM");
        request.setPrivateKeyFile(keyFile);
        request.setCertFile(certFile);
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<byte[]> response = certSignController.signPDFWithCert(request);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
    }

    @Test
    void testSignPdfWithCer() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile keyFile =
                new MockMultipartFile(
                        "privateKeyFile", "test-key.key", "application/x-pem-file", keyBytes);
        MockMultipartFile certFile =
                new MockMultipartFile(
                        "certFile", "test-cert.cer", "application/x-x509-ca-cert", cerCertBytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PEM");
        request.setPrivateKeyFile(keyFile);
        request.setCertFile(certFile);
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<byte[]> response = certSignController.signPDFWithCert(request);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
    }

    @Test
    void testSignPdfWithDer() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile keyFile =
                new MockMultipartFile(
                        "privateKeyFile", "test-key.key", "application/x-pem-file", keyBytes);
        MockMultipartFile certFile =
                new MockMultipartFile(
                        "certFile", "test-cert.der", "application/x-x509-ca-cert", derCertBytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PEM");
        request.setPrivateKeyFile(keyFile);
        request.setCertFile(certFile);
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<byte[]> response = certSignController.signPDFWithCert(request);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
    }
}
