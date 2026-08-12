package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.security.auth.x500.X500Principal;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.examples.signature.CreateSignatureBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import stirling.software.common.service.SsrfProtectionService;

class PadesLtvServiceTest {

    private byte[] signedPdf;
    private Certificate[] certificateChain;
    private SsrfProtectionService ssrfProtectionService;

    @BeforeEach
    void setUp() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = new ClassPathResource("certs/test-cert.p12").getInputStream()) {
            keyStore.load(input, "password".toCharArray());
        }
        String alias = keyStore.aliases().nextElement();
        certificateChain = keyStore.getCertificateChain(alias);
        CreateSignatureBase signer = new CreateSignatureBase(keyStore, "password".toCharArray()) {};
        signedPdf = createSignedPdf(signer);
        ssrfProtectionService = mock(SsrfProtectionService.class);
        when(ssrfProtectionService.isUrlAllowed(anyString())).thenReturn(true);
    }

    @Test
    void addsCertificateDssAndVriWithoutChangingTheSignatureCount() throws Exception {
        PadesLtvService.ValidationTransport transport =
                mock(PadesLtvService.ValidationTransport.class);
        PadesLtvService service =
                new PadesLtvService(
                        ssrfProtectionService, transport, (url, content) -> new byte[] {1, 2, 3});

        byte[] result = service.addValidationData(signedPdf, certificateChain, false, null);

        try (PDDocument document = Loader.loadPDF(result)) {
            assertEquals(1, document.getSignatureDictionaries().size());
            COSDictionary dss =
                    (COSDictionary)
                            document.getDocumentCatalog()
                                    .getCOSObject()
                                    .getDictionaryObject(COSName.getPDFName("DSS"));
            assertNotNull(dss);
            COSArray certificates = (COSArray) dss.getDictionaryObject(COSName.getPDFName("Certs"));
            assertEquals(1, certificates.size());
            COSDictionary vri = (COSDictionary) dss.getDictionaryObject(COSName.getPDFName("VRI"));
            assertEquals(1, vri.size());
            assertEquals(40, vri.keySet().iterator().next().getName().length());
        }
        verify(transport, never()).send(null, null, null);
    }

    @Test
    void addsArchiveDocumentTimestampAfterTheDssRevision() throws Exception {
        AtomicBoolean timestampRequested = new AtomicBoolean();
        PadesLtvService service =
                new PadesLtvService(
                        ssrfProtectionService,
                        (uri, contentType, body) -> new byte[0],
                        (url, content) -> {
                            timestampRequested.set(true);
                            assertTrue(content.readAllBytes().length > signedPdf.length);
                            return new byte[] {1, 2, 3};
                        });

        byte[] result =
                service.addValidationData(
                        signedPdf, certificateChain, true, "https://tsa.example.test");

        assertTrue(timestampRequested.get());
        try (PDDocument document = Loader.loadPDF(result)) {
            assertEquals(2, document.getSignatureDictionaries().size());
            PDSignature documentTimestamp = document.getSignatureDictionaries().getLast();
            assertEquals(
                    "DocTimeStamp", documentTimestamp.getCOSObject().getNameAsString(COSName.TYPE));
            assertEquals("ETSI.RFC3161", documentTimestamp.getSubFilter());
            assertNotNull(
                    document.getDocumentCatalog()
                            .getCOSObject()
                            .getDictionaryObject(COSName.getPDFName("DSS")));
        }
    }

    @Test
    void downloadsAndValidatesCrlEvidenceForIssuedCertificates() throws Exception {
        KeyPair issuerKeys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        KeyPair signerKeys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        X500Name issuerName = new X500Name("CN=Test Issuer");
        X509Certificate issuer =
                createCertificate(issuerName, issuerName, issuerKeys, issuerKeys, true, null);
        String crlUrl = "https://crl.example.test/signer.crl";
        X509Certificate signer =
                createCertificate(
                        issuerName,
                        new X500Name("CN=Test Signer"),
                        issuerKeys,
                        signerKeys,
                        false,
                        crlUrl);
        X509CRL crl = createCrl(issuerName, issuerKeys);
        byte[] crlBytes = crl.getEncoded();
        PadesLtvService.ValidationTransport transport =
                (uri, contentType, body) -> {
                    assertEquals(crlUrl, uri.toString());
                    assertEquals("application/pkix-crl", contentType);
                    return crlBytes;
                };
        PadesLtvService service =
                new PadesLtvService(
                        ssrfProtectionService, transport, (url, content) -> new byte[] {1});

        byte[] result =
                service.addValidationData(
                        signedPdf, new Certificate[] {signer, issuer}, false, null);

        try (PDDocument document = Loader.loadPDF(result)) {
            COSDictionary dss =
                    (COSDictionary)
                            document.getDocumentCatalog()
                                    .getCOSObject()
                                    .getDictionaryObject(COSName.getPDFName("DSS"));
            COSArray crls = (COSArray) dss.getDictionaryObject(COSName.getPDFName("CRLs"));
            assertEquals(1, crls.size());
        }
    }

    private byte[] createSignedPdf(CreateSignatureBase signer) throws Exception {
        byte[] unsignedPdf;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            unsignedPdf = output.toByteArray();
        }
        try (PDDocument document = Loader.loadPDF(unsignedPdf)) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
            signature.setSignDate(Calendar.getInstance());
            document.addSignature(signature, signer);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.saveIncremental(output);
            return output.toByteArray();
        }
    }

    private X509Certificate createCertificate(
            X500Name issuerName,
            X500Name subjectName,
            KeyPair issuerKeys,
            KeyPair subjectKeys,
            boolean ca,
            String crlUrl)
            throws Exception {
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder =
                new JcaX509v3CertificateBuilder(
                        issuerName,
                        BigInteger.valueOf(Math.abs(subjectName.hashCode()) + 1L),
                        Date.from(now.minusSeconds(60)),
                        Date.from(now.plusSeconds(3600)),
                        subjectName,
                        subjectKeys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        if (crlUrl != null) {
            GeneralNames names =
                    new GeneralNames(
                            new GeneralName(GeneralName.uniformResourceIdentifier, crlUrl));
            DistributionPoint point =
                    new DistributionPoint(new DistributionPointName(names), null, null);
            builder.addExtension(
                    Extension.cRLDistributionPoints,
                    false,
                    new CRLDistPoint(new DistributionPoint[] {point}));
        }
        ContentSigner contentSigner =
                new JcaContentSignerBuilder("SHA256WithRSA").build(issuerKeys.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(contentSigner));
    }

    private X509CRL createCrl(X500Name issuerName, KeyPair issuerKeys) throws Exception {
        Instant now = Instant.now();
        JcaX509v2CRLBuilder builder =
                new JcaX509v2CRLBuilder(
                        new X500Principal(issuerName.toString()), Date.from(now.minusSeconds(60)));
        builder.setNextUpdate(Date.from(now.plusSeconds(3600)));
        ContentSigner contentSigner =
                new JcaContentSignerBuilder("SHA256WithRSA").build(issuerKeys.getPrivate());
        return new JcaX509CRLConverter().getCRL(builder.build(contentSigner));
    }
}
