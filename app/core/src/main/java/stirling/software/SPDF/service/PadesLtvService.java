package stirling.software.SPDF.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.examples.signature.ValidationTimeStamp;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.springframework.stereotype.Service;

import stirling.software.common.service.SsrfProtectionService;

@Service
public class PadesLtvService {

    private static final COSName DSS = COSName.getPDFName("DSS");
    private static final COSName VRI = COSName.getPDFName("VRI");
    private static final COSName CERTS = COSName.getPDFName("Certs");
    private static final COSName OCSPS = COSName.getPDFName("OCSPs");
    private static final COSName CRLS = COSName.getPDFName("CRLs");
    private static final COSName CERT = COSName.getPDFName("Cert");
    private static final COSName OCSP = COSName.getPDFName("OCSP");
    private static final COSName CRL = COSName.getPDFName("CRL");
    private static final COSName TU = COSName.getPDFName("TU");
    private static final COSName DOC_TIME_STAMP = COSName.getPDFName("DocTimeStamp");
    private static final int MAX_VALIDATION_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final int TIMESTAMP_SIGNATURE_SIZE = 32 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SsrfProtectionService ssrfProtectionService;
    private final ValidationTransport transport;
    private final TimestampTokenProvider timestampTokenProvider;

    public PadesLtvService(SsrfProtectionService ssrfProtectionService) {
        this(ssrfProtectionService, PadesLtvService::sendHttp, PadesLtvService::requestTimestamp);
    }

    PadesLtvService(
            SsrfProtectionService ssrfProtectionService,
            ValidationTransport transport,
            TimestampTokenProvider timestampTokenProvider) {
        this.ssrfProtectionService = ssrfProtectionService;
        this.transport = transport;
        this.timestampTokenProvider = timestampTokenProvider;
    }

    public byte[] addValidationData(
            byte[] signedPdf,
            Certificate[] certificateChain,
            boolean archiveTimestamp,
            String tsaUrl)
            throws IOException {
        List<X509Certificate> certificates = toX509Certificates(certificateChain);
        ValidationMaterial validationMaterial = collectValidationMaterial(certificates);
        byte[] ltvPdf = appendDss(signedPdf, validationMaterial);
        return archiveTimestamp ? appendDocumentTimestamp(ltvPdf, tsaUrl) : ltvPdf;
    }

    public void validateTimestampUrl(String tsaUrl) throws IOException {
        validateRemoteUrl(tsaUrl, "TSA");
    }

    private ValidationMaterial collectValidationMaterial(List<X509Certificate> certificates)
            throws IOException {
        List<byte[]> ocspResponses = new ArrayList<>();
        List<byte[]> crls = new ArrayList<>();
        for (X509Certificate certificate : certificates) {
            if (isSelfSigned(certificate)) {
                continue;
            }
            X509Certificate issuer = findIssuer(certificate, certificates);
            boolean evidenceFound = false;
            for (String ocspUrl : getOcspUrls(certificate)) {
                try {
                    ocspResponses.add(fetchOcsp(ocspUrl, certificate, issuer));
                    evidenceFound = true;
                    break;
                } catch (IOException e) {
                    // Try the next advertised responder, then CRL fallback.
                }
            }
            if (!evidenceFound) {
                for (String crlUrl : getCrlUrls(certificate)) {
                    try {
                        crls.add(fetchCrl(crlUrl, issuer));
                        evidenceFound = true;
                        break;
                    } catch (IOException e) {
                        // Try the next distribution point.
                    }
                }
            }
            if (!evidenceFound) {
                throw new IOException(
                        "No valid OCSP or CRL evidence could be collected for "
                                + certificate.getSubjectX500Principal());
            }
        }
        List<byte[]> encodedCertificates = new ArrayList<>();
        try {
            for (X509Certificate certificate : certificates) {
                encodedCertificates.add(certificate.getEncoded());
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Unable to encode the certificate chain", e);
        }
        return new ValidationMaterial(encodedCertificates, ocspResponses, crls);
    }

    private byte[] appendDss(byte[] signedPdf, ValidationMaterial material) throws IOException {
        try (PDDocument document = Loader.loadPDF(signedPdf)) {
            List<PDSignature> signatures = document.getSignatureDictionaries();
            if (signatures.isEmpty()) {
                throw new IOException("PAdES validation data requires a signed PDF");
            }

            COSArray certStreams = createStreams(document, material.certificates());
            COSArray ocspStreams = createStreams(document, material.ocspResponses());
            COSArray crlStreams = createStreams(document, material.crls());
            COSDictionary vriEntry = new COSDictionary();
            vriEntry.setItem(CERT, certStreams);
            if (ocspStreams.size() > 0) {
                vriEntry.setItem(OCSP, ocspStreams);
            }
            if (crlStreams.size() > 0) {
                vriEntry.setItem(CRL, crlStreams);
            }
            vriEntry.setDate(TU, Calendar.getInstance());

            PDSignature latestSignature = signatures.getLast();
            String vriKey = signatureHash(latestSignature, signedPdf);
            COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
            COSDictionary dss =
                    catalog.getDictionaryObject(DSS) instanceof COSDictionary existingDss
                            ? existingDss
                            : new COSDictionary();
            COSDictionary vri =
                    dss.getDictionaryObject(VRI) instanceof COSDictionary existingVri
                            ? existingVri
                            : new COSDictionary();
            vri.setItem(COSName.getPDFName(vriKey), vriEntry);

            dss.setItem(CERTS, mergeArrays(dss, CERTS, certStreams));
            if (ocspStreams.size() > 0) {
                dss.setItem(OCSPS, mergeArrays(dss, OCSPS, ocspStreams));
            }
            if (crlStreams.size() > 0) {
                dss.setItem(CRLS, mergeArrays(dss, CRLS, crlStreams));
            }
            dss.setItem(VRI, vri);
            catalog.setItem(DSS, dss);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.saveIncremental(output);
            return output.toByteArray();
        }
    }

    private byte[] appendDocumentTimestamp(byte[] ltvPdf, String tsaUrl) throws IOException {
        validateRemoteUrl(tsaUrl, "TSA");
        try (PDDocument document = Loader.loadPDF(ltvPdf);
                SignatureOptions options = new SignatureOptions()) {
            PDSignature timestamp = new PDSignature();
            timestamp.getCOSObject().setName(COSName.TYPE, DOC_TIME_STAMP.getName());
            timestamp.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            timestamp.setSubFilter(COSName.getPDFName("ETSI.RFC3161"));
            timestamp.setSignDate(Calendar.getInstance());
            options.setPreferredSignatureSize(TIMESTAMP_SIGNATURE_SIZE);
            document.addSignature(
                    timestamp, content -> timestampTokenProvider.get(tsaUrl, content), options);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.saveIncremental(output);
            return output.toByteArray();
        }
    }

    private byte[] fetchOcsp(String url, X509Certificate certificate, X509Certificate issuer)
            throws IOException {
        try {
            DigestCalculator digestCalculator =
                    new JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1);
            CertificateID certificateId =
                    new CertificateID(
                            digestCalculator,
                            new JcaX509CertificateHolder(issuer),
                            certificate.getSerialNumber());
            OCSPReqBuilder builder = new OCSPReqBuilder();
            builder.addRequest(certificateId);
            ExtensionsGenerator extensions = new ExtensionsGenerator();
            byte[] nonce = new byte[16];
            SECURE_RANDOM.nextBytes(nonce);
            extensions.addExtension(
                    OCSPObjectIdentifiers.id_pkix_ocsp_nonce, false, new DEROctetString(nonce));
            builder.setRequestExtensions(extensions.generate());
            OCSPReq request = builder.build();
            byte[] responseBytes = request(url, "application/ocsp-request", request.getEncoded());
            OCSPResp response = new OCSPResp(responseBytes);
            if (response.getStatus() != OCSPRespBuilder.SUCCESSFUL
                    || !(response.getResponseObject() instanceof BasicOCSPResp basicResponse)) {
                throw new IOException("OCSP responder did not return a successful basic response");
            }
            if (!isValidOcspSignature(basicResponse, issuer)) {
                throw new IOException("OCSP response signature is invalid");
            }
            if (basicResponse.getResponses().length == 0
                    || !certificateId.equals(basicResponse.getResponses()[0].getCertID())) {
                throw new IOException("OCSP response does not match the signing certificate");
            }
            if (basicResponse.getResponses()[0].getCertStatus() != CertificateStatus.GOOD) {
                throw new IOException("OCSP responder did not report the certificate as good");
            }
            if (basicResponse.getResponses()[0].getNextUpdate() != null
                    && basicResponse
                            .getResponses()[0]
                            .getNextUpdate()
                            .before(Calendar.getInstance().getTime())) {
                throw new IOException("OCSP response is expired");
            }
            return basicResponse.getEncoded();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to collect OCSP evidence", e);
        }
    }

    private byte[] fetchCrl(String url, X509Certificate issuer) throws IOException {
        byte[] bytes = request(url, "application/pkix-crl", null);
        try {
            X509CRL crl =
                    (X509CRL)
                            CertificateFactory.getInstance("X.509")
                                    .generateCRL(new ByteArrayInputStream(bytes));
            crl.verify(issuer.getPublicKey());
            if (crl.getNextUpdate() != null
                    && crl.getNextUpdate().before(Calendar.getInstance().getTime())) {
                throw new IOException("Downloaded CRL is expired");
            }
            return crl.getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IOException("Downloaded CRL could not be validated", e);
        }
    }

    private byte[] request(String url, String contentType, byte[] body) throws IOException {
        validateRemoteUrl(url, "validation data");
        try {
            byte[] response = transport.send(URI.create(url), contentType, body);
            if (response.length == 0 || response.length > MAX_VALIDATION_RESPONSE_BYTES) {
                throw new IOException("Validation response size is invalid");
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Validation data request was interrupted", e);
        }
    }

    private void validateRemoteUrl(String url, String purpose) throws IOException {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException(purpose + " URL is invalid", e);
        }
        if (!("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IOException(purpose + " URL must use HTTP or HTTPS");
        }
        if (!ssrfProtectionService.isUrlAllowed(uri.toString())) {
            throw new IOException(purpose + " URL is blocked by the SSRF policy");
        }
    }

    private static byte[] sendHttp(URI uri, String contentType, byte[] body)
            throws IOException, InterruptedException {
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        HttpRequest.Builder request =
                HttpRequest.newBuilder(uri)
                        .timeout(REQUEST_TIMEOUT)
                        .header(
                                "Accept",
                                "application/ocsp-request".equals(contentType)
                                        ? "application/ocsp-response"
                                        : contentType)
                        .header("User-Agent", "Stirling-PDF-PAdES-LTV/1.0");
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        }
        HttpResponse<byte[]> response =
                client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Validation endpoint returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static byte[] requestTimestamp(String tsaUrl, InputStream content) throws IOException {
        try {
            return new ValidationTimeStamp(tsaUrl).getTimeStampToken(content);
        } catch (GeneralSecurityException | java.net.URISyntaxException e) {
            throw new IOException("Unable to initialise timestamp request", e);
        }
    }

    private COSArray createStreams(PDDocument document, Collection<byte[]> values)
            throws IOException {
        COSArray streams = new COSArray();
        Map<String, byte[]> uniqueValues = new LinkedHashMap<>();
        for (byte[] value : values) {
            uniqueValues.putIfAbsent(sha256(value), value);
        }
        for (byte[] value : uniqueValues.values()) {
            PDStream stream = new PDStream(document);
            try (OutputStream output = stream.createOutputStream(COSName.FLATE_DECODE)) {
                output.write(value);
            }
            streams.add(stream);
        }
        return streams;
    }

    private COSArray mergeArrays(COSDictionary dictionary, COSName key, COSArray additions) {
        COSArray merged =
                dictionary.getDictionaryObject(key) instanceof COSArray existing
                        ? existing
                        : new COSArray();
        for (int index = 0; index < additions.size(); index++) {
            merged.add(additions.get(index));
        }
        return merged;
    }

    private boolean isValidOcspSignature(BasicOCSPResp response, X509Certificate issuer)
            throws Exception {
        if (response.isSignatureValid(
                new JcaContentVerifierProviderBuilder().build(issuer.getPublicKey()))) {
            return true;
        }
        JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
        for (var holder : response.getCerts()) {
            X509Certificate responder = converter.getCertificate(holder);
            try {
                responder.checkValidity();
                responder.verify(issuer.getPublicKey());
                List<String> extendedKeyUsage = responder.getExtendedKeyUsage();
                if (extendedKeyUsage == null
                        || !extendedKeyUsage.contains(KeyPurposeId.id_kp_OCSPSigning.getId())) {
                    continue;
                }
                if (response.isSignatureValid(
                        new JcaContentVerifierProviderBuilder().build(responder.getPublicKey()))) {
                    return true;
                }
            } catch (GeneralSecurityException e) {
                // Try the next responder certificate.
            }
        }
        return false;
    }

    private String signatureHash(PDSignature signature, byte[] pdf) throws IOException {
        return toHex(digest("SHA-1", signature.getContents(new ByteArrayInputStream(pdf))));
    }

    private String sha256(byte[] value) throws IOException {
        return toHex(digest("SHA-256", value));
    }

    private byte[] digest(String algorithm, byte[] value) throws IOException {
        try {
            return MessageDigest.getInstance(algorithm).digest(value);
        } catch (GeneralSecurityException e) {
            throw new IOException("Digest algorithm is unavailable: " + algorithm, e);
        }
    }

    private String toHex(byte[] value) {
        return java.util.HexFormat.of().withUpperCase().formatHex(value);
    }

    private List<X509Certificate> toX509Certificates(Certificate[] chain) throws IOException {
        List<X509Certificate> certificates = new ArrayList<>();
        for (Certificate certificate : chain) {
            if (!(certificate instanceof X509Certificate x509Certificate)) {
                throw new IOException("PAdES validation data requires an X.509 certificate chain");
            }
            certificates.add(x509Certificate);
        }
        if (certificates.isEmpty()) {
            throw new IOException("PAdES validation data requires a certificate chain");
        }
        return certificates;
    }

    private X509Certificate findIssuer(
            X509Certificate certificate, List<X509Certificate> certificates) throws IOException {
        return certificates.stream()
                .filter(
                        candidate ->
                                certificate
                                        .getIssuerX500Principal()
                                        .equals(candidate.getSubjectX500Principal()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IOException(
                                        "Certificate chain does not contain issuer for "
                                                + certificate.getSubjectX500Principal()));
    }

    private boolean isSelfSigned(X509Certificate certificate) {
        if (!certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
            return false;
        }
        try {
            certificate.verify(certificate.getPublicKey());
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private List<String> getOcspUrls(X509Certificate certificate) throws IOException {
        byte[] extension = certificate.getExtensionValue(Extension.authorityInfoAccess.getId());
        if (extension == null) {
            return List.of();
        }
        AuthorityInformationAccess access =
                AuthorityInformationAccess.getInstance(unwrapExtension(extension));
        return Arrays.stream(access.getAccessDescriptions())
                .filter(
                        description ->
                                AccessDescription.id_ad_ocsp.equals(description.getAccessMethod()))
                .map(AccessDescription::getAccessLocation)
                .filter(name -> name.getTagNo() == GeneralName.uniformResourceIdentifier)
                .map(name -> DERIA5String.getInstance(name.getName()).getString())
                .toList();
    }

    private List<String> getCrlUrls(X509Certificate certificate) throws IOException {
        byte[] extension = certificate.getExtensionValue(Extension.cRLDistributionPoints.getId());
        if (extension == null) {
            return List.of();
        }
        CRLDistPoint distributionPoints = CRLDistPoint.getInstance(unwrapExtension(extension));
        List<String> urls = new ArrayList<>();
        for (DistributionPoint point : distributionPoints.getDistributionPoints()) {
            DistributionPointName name = point.getDistributionPoint();
            if (name == null || name.getType() != DistributionPointName.FULL_NAME) {
                continue;
            }
            for (GeneralName generalName : GeneralNames.getInstance(name.getName()).getNames()) {
                if (generalName.getTagNo() == GeneralName.uniformResourceIdentifier) {
                    urls.add(DERIA5String.getInstance(generalName.getName()).getString());
                }
            }
        }
        return urls;
    }

    private ASN1Primitive unwrapExtension(byte[] extension) throws IOException {
        ASN1OctetString octets =
                ASN1OctetString.getInstance(ASN1Primitive.fromByteArray(extension));
        return ASN1Primitive.fromByteArray(octets.getOctets());
    }

    record ValidationMaterial(
            List<byte[]> certificates, List<byte[]> ocspResponses, List<byte[]> crls) {}

    @FunctionalInterface
    interface ValidationTransport {
        byte[] send(URI uri, String contentType, byte[] body)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface TimestampTokenProvider {
        byte[] get(String tsaUrl, InputStream content) throws IOException;
    }
}
