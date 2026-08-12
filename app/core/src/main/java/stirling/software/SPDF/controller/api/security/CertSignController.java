package stirling.software.SPDF.controller.api.security;

import java.awt.*;
import java.beans.PropertyEditorSupport;
import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.examples.signature.CreateSignatureBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.util.Matrix;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.CMSTypedData;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMDecryptorProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.micrometer.common.util.StringUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.config.swagger.StandardPdfResponse;
import stirling.software.SPDF.model.api.security.SignPDFWithCertRequest;
import stirling.software.SPDF.service.KmsSignatureService;
import stirling.software.SPDF.service.KmsSignatureService.KmsSignatureAlgorithm;
import stirling.software.SPDF.service.KmsSignatureService.KmsSigningRequest;
import stirling.software.SPDF.service.KmsSignatureService.SignerProvider;
import stirling.software.SPDF.service.PadesLtvService;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.service.ServerCertificateServiceInterface;
import stirling.software.common.util.ExceptionUtils;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.WebResponseUtils;

@RestController
@RequestMapping("/api/v1/security")
@Slf4j
@Tag(name = "Security", description = "Security APIs")
public class CertSignController {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(
                MultipartFile.class,
                new PropertyEditorSupport() {
                    @Override
                    public void setAsText(String text) throws IllegalArgumentException {
                        setValue(null);
                    }
                });
    }

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final ServerCertificateServiceInterface serverCertificateService;
    private final KmsSignatureService kmsSignatureService;
    private final PadesLtvService padesLtvService;

    public CertSignController(
            CustomPDFDocumentFactory pdfDocumentFactory,
            @Autowired(required = false) ServerCertificateServiceInterface serverCertificateService,
            KmsSignatureService kmsSignatureService,
            PadesLtvService padesLtvService) {
        this.pdfDocumentFactory = pdfDocumentFactory;
        this.serverCertificateService = serverCertificateService;
        this.kmsSignatureService = kmsSignatureService;
        this.padesLtvService = padesLtvService;
    }

    private static void sign(
            CustomPDFDocumentFactory pdfDocumentFactory,
            MultipartFile input,
            OutputStream output,
            VisibleCreateSignature instance,
            Boolean showSignature,
            Integer pageNumber,
            String name,
            String location,
            String reason,
            Boolean showLogo,
            byte[] signatureImage,
            String signatureText,
            String signatureFieldName,
            org.apache.pdfbox.cos.COSName subFilter)
            throws IOException {
        try (PDDocument doc = pdfDocumentFactory.load(input)) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(subFilter);
            signature.setName(name);
            signature.setLocation(location);
            signature.setReason(reason);
            signature.setSignDate(Calendar.getInstance()); // PDFBox requires Calendar
            PDSignatureField targetField = findTargetSignatureField(doc, signatureFieldName);
            if (targetField != null) {
                targetField.setValue(signature);
                pageNumber = findFieldPage(doc, targetField);
            }
            validateVisibleSignaturePage(showSignature, pageNumber, doc);
            if (Boolean.TRUE.equals(showSignature)) {
                try (SignatureOptions signatureOptions = new SignatureOptions()) {
                    signatureOptions.setVisualSignature(
                            instance.createVisibleSignature(
                                    doc,
                                    signature,
                                    pageNumber,
                                    showLogo,
                                    signatureImage,
                                    signatureText,
                                    targetField));
                    signatureOptions.setPage(pageNumber);

                    doc.addSignature(signature, instance, signatureOptions);
                    doc.saveIncremental(output);
                }
            } else {
                doc.addSignature(signature, instance);
                doc.saveIncremental(output);
            }
        } catch (Exception e) {
            ExceptionUtils.logException("PDF signing", e);
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("PDF signing failed", e);
        }
    }

    @AutoJobPostMapping(
            consumes = {
                MediaType.MULTIPART_FORM_DATA_VALUE,
                MediaType.APPLICATION_FORM_URLENCODED_VALUE
            },
            value = "/cert-sign")
    @StandardPdfResponse
    @Operation(
            summary = "Sign PDF with a Digital Certificate",
            description =
                    "This endpoint accepts a PDF file, a digital certificate and related"
                            + " information to sign the PDF. It then returns the digitally signed PDF"
                            + " file. Input:PDF Output:PDF Type:SISO")
    public ResponseEntity<byte[]> signPDFWithCert(@ModelAttribute SignPDFWithCertRequest request)
            throws Exception {
        MultipartFile pdf = request.getFileInput();
        String certType = request.getCertType();
        MultipartFile privateKeyFile = request.getPrivateKeyFile();
        MultipartFile certFile = request.getCertFile();
        MultipartFile p12File = request.getP12File();
        MultipartFile jksfile = request.getJksFile();
        String password = request.getPassword();
        Boolean showSignature = request.getShowSignature();
        String reason = request.getReason();
        String location = request.getLocation();
        String name = request.getName();
        String kmsKeyId = request.getKmsKeyId();
        // Convert 1-indexed page number (user input) to 0-indexed page number (API requirement)
        Integer pageNumber = request.getPageNumber() != null ? (request.getPageNumber() - 1) : null;
        Boolean showLogo = request.getShowLogo();
        byte[] signatureImage =
                request.getSignatureImage() == null || request.getSignatureImage().isEmpty()
                        ? null
                        : request.getSignatureImage().getBytes();
        if (signatureImage != null && signatureImage.length > 5 * 1024 * 1024) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "Invalid argument: {0}",
                    "signature image exceeds 5 MB");
        }
        String padesProfile =
                (StringUtils.isBlank(request.getPadesProfile()) ? "B_B" : request.getPadesProfile())
                        .toUpperCase(Locale.ROOT);
        if (!List.of("B_B", "B_T", "B_LT", "B_LTA").contains(padesProfile)) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "Invalid argument: {0}", "unknown PAdES profile");
        }
        if (!"B_B".equals(padesProfile) && StringUtils.isBlank(request.getTsaUrl())) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "Invalid argument: {0}",
                    "TSA URL is required for PAdES " + padesProfile.replace('_', '-'));
        }
        if (!"B_B".equals(padesProfile)) {
            padesLtvService.validateTimestampUrl(request.getTsaUrl());
        }

        if (StringUtils.isBlank(certType)) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.optionsNotSpecified",
                    "{0} options are not specified",
                    "certificate type");
        }

        KeyStore ks = null;
        String keystorePassword = password;
        Certificate[] kmsCertificateChain = null;
        KmsSignatureAlgorithm kmsSignatureAlgorithm = null;
        SignerProvider signerProvider = SignerProvider.KMS;

        switch (certType) {
            case "PEM":
                privateKeyFile =
                        validateFilePresent(
                                privateKeyFile, "PEM private key", "private key file is required");
                certFile =
                        validateFilePresent(
                                certFile, "PEM certificate", "certificate file is required");
                ks = KeyStore.getInstance("JKS");
                ks.load(null);
                PrivateKey privateKey = getPrivateKeyFromPEM(privateKeyFile.getBytes(), password);
                Certificate cert = (Certificate) getCertificateFromPEM(certFile.getBytes());
                ks.setKeyEntry(
                        "alias", privateKey, password.toCharArray(), new Certificate[] {cert});
                break;
            case "PKCS12":
            case "PFX":
                p12File =
                        validateFilePresent(
                                p12File, "PKCS12 keystore", "PKCS12/PFX keystore file is required");
                ks = KeyStore.getInstance("PKCS12");
                ks.load(p12File.getInputStream(), password.toCharArray());
                break;
            case "JKS":
                jksfile =
                        validateFilePresent(
                                jksfile, "JKS keystore", "JKS keystore file is required");
                ks = KeyStore.getInstance("JKS");
                ks.load(jksfile.getInputStream(), password.toCharArray());
                break;
            case "SERVER":
                if (serverCertificateService == null) {
                    throw ExceptionUtils.createIllegalArgumentException(
                            "error.serverCertificateNotAvailable",
                            "Server certificate service is not available in this edition");
                }
                if (!serverCertificateService.isEnabled()) {
                    throw ExceptionUtils.createIllegalArgumentException(
                            "error.serverCertificateDisabled",
                            "Server certificate feature is disabled");
                }
                if (!serverCertificateService.hasServerCertificate()) {
                    throw ExceptionUtils.createIllegalArgumentException(
                            "error.serverCertificateNotFound", "No server certificate configured");
                }
                ks = serverCertificateService.getServerKeyStore();
                keystorePassword = serverCertificateService.getServerCertificatePassword();
                break;
            case "KMS":
                try {
                    signerProvider = SignerProvider.from(request.getSignerProvider());
                } catch (IllegalArgumentException e) {
                    throw ExceptionUtils.createIllegalArgumentException(
                            "error.invalidArgument",
                            "Invalid argument: {0}",
                            "unknown managed signer provider");
                }
                if (!kmsSignatureService.isEnabled(signerProvider)) {
                    throw ExceptionUtils.createIllegalArgumentException(
                            "error.kmsSigningDisabled",
                            signerProvider.getLabel() + " signing is not enabled");
                }
                certFile =
                        validateFilePresent(
                                certFile,
                                "KMS certificate chain",
                                "certificate chain file is required");
                kmsSignatureAlgorithm =
                        resolveKmsSignatureAlgorithm(
                                signerProvider, request.getKmsSignatureAlgorithm());
                kmsCertificateChain = getCertificatesFromPEM(certFile.getBytes());
                validateKmsCertificate(kmsCertificateChain, kmsSignatureAlgorithm);
                break;
            default:
                throw ExceptionUtils.createIllegalArgumentException(
                        "error.invalidArgument",
                        "Invalid argument: {0}",
                        "certificate type: " + certType);
        }

        VisibleCreateSignature createSignature;
        org.apache.pdfbox.cos.COSName subFilter = PDSignature.SUBFILTER_ETSI_CADES_DETACHED;
        if ("KMS".equals(certType)) {
            createSignature =
                    new KmsCreateSignature(
                            kmsCertificateChain,
                            kmsSignatureService,
                            signerProvider,
                            kmsKeyId,
                            kmsSignatureAlgorithm);
        } else {
            createSignature = new CreateSignature(ks, keystorePassword.toCharArray());
        }
        if (!"B_B".equals(padesProfile)) {
            createSignature.setTsaUrl(request.getTsaUrl());
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        sign(
                pdfDocumentFactory,
                pdf,
                baos,
                createSignature,
                showSignature,
                pageNumber,
                name,
                location,
                reason,
                showLogo,
                signatureImage,
                request.getSignatureText(),
                request.getSignatureFieldName(),
                subFilter);
        byte[] signedPdf = baos.toByteArray();
        if (List.of("B_LT", "B_LTA").contains(padesProfile)) {
            signedPdf =
                    padesLtvService.addValidationData(
                            signedPdf,
                            createSignature.getCertificateChain(),
                            "B_LTA".equals(padesProfile),
                            request.getTsaUrl());
        }
        // Return the signed PDF
        return WebResponseUtils.bytesToWebResponse(
                signedPdf, GeneralUtils.generateFilename(pdf.getOriginalFilename(), "_signed.pdf"));
    }

    private MultipartFile validateFilePresent(
            MultipartFile file, String argumentName, String errorDescription) {
        if (file == null || file.isEmpty()) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "Invalid argument: {0}",
                    argumentName + " - " + errorDescription);
        }
        return file;
    }

    private static void validateVisibleSignaturePage(
            Boolean showSignature, Integer pageNumber, PDDocument doc) throws IOException {
        if (!Boolean.TRUE.equals(showSignature)) {
            return;
        }
        if (pageNumber == null) {
            throw new IOException("Visible signature page number is required");
        }
        if (pageNumber < 0 || pageNumber >= doc.getNumberOfPages()) {
            throw new IOException(
                    "Visible signature page number must reference an existing PDF page");
        }
    }

    private static PDSignatureField findTargetSignatureField(PDDocument document, String fieldName)
            throws IOException {
        if (StringUtils.isBlank(fieldName)) {
            return null;
        }
        PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
        PDField field = acroForm == null ? null : acroForm.getField(fieldName);
        if (!(field instanceof PDSignatureField signatureField)) {
            throw new IOException("Signature field does not exist: " + fieldName);
        }
        if (signatureField.getSignature() != null) {
            throw new IOException("Signature field is already signed: " + fieldName);
        }
        return signatureField;
    }

    private static int findFieldPage(PDDocument document, PDSignatureField signatureField)
            throws IOException {
        PDAnnotationWidget widget = signatureField.getWidgets().getFirst();
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
            if (document.getPage(pageIndex).getAnnotations().contains(widget)) {
                return pageIndex;
            }
        }
        throw new IOException("Signature field is not attached to a PDF page");
    }

    private KmsSignatureAlgorithm resolveKmsSignatureAlgorithm(
            SignerProvider signerProvider, String requestAlgorithm) {
        try {
            return kmsSignatureService.resolveAlgorithm(signerProvider, requestAlgorithm);
        } catch (IllegalArgumentException e) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "Invalid argument: {0}", e.getMessage());
        }
    }

    private PrivateKey getPrivateKeyFromPEM(byte[] pemBytes, String password)
            throws IOException, OperatorCreationException, PKCSException {
        try (PEMParser pemParser =
                new PEMParser(new InputStreamReader(new ByteArrayInputStream(pemBytes)))) {
            Object pemObject = pemParser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            PrivateKeyInfo pkInfo;
            if (pemObject instanceof PKCS8EncryptedPrivateKeyInfo pkcs8EncryptedPrivateKeyInfo) {
                InputDecryptorProvider decProv =
                        new JceOpenSSLPKCS8DecryptorProviderBuilder().build(password.toCharArray());
                pkInfo = pkcs8EncryptedPrivateKeyInfo.decryptPrivateKeyInfo(decProv);
            } else if (pemObject instanceof PEMEncryptedKeyPair pemEncryptedKeyPair) {
                PEMDecryptorProvider decProv =
                        new JcePEMDecryptorProviderBuilder().build(password.toCharArray());
                pkInfo = pemEncryptedKeyPair.decryptKeyPair(decProv).getPrivateKeyInfo();
            } else {
                pkInfo = ((PEMKeyPair) pemObject).getPrivateKeyInfo();
            }
            return converter.getPrivateKey(pkInfo);
        }
    }

    private Certificate getCertificateFromPEM(byte[] pemBytes)
            throws IOException, CertificateException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(pemBytes)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(bis);
        }
    }

    private Certificate[] getCertificatesFromPEM(byte[] pemBytes)
            throws IOException, CertificateException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(pemBytes)) {
            Collection<? extends Certificate> certificates =
                    CertificateFactory.getInstance("X.509").generateCertificates(bis);
            if (certificates.isEmpty()) {
                throw new CertificateException("No certificates found in certificate chain file");
            }
            return certificates.toArray(Certificate[]::new);
        }
    }

    private void validateKmsCertificate(
            Certificate[] certificateChain, KmsSignatureAlgorithm signatureAlgorithm) {
        if (!(certificateChain[0] instanceof X509Certificate signingCertificate)) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "Invalid argument: {0}",
                    "KMS certificate chain must start with an X.509 signing certificate");
        }
        String publicKeyAlgorithm = signingCertificate.getPublicKey().getAlgorithm();
        if (!signatureAlgorithm.getCertificateKeyAlgorithm().equalsIgnoreCase(publicKeyAlgorithm)) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "Invalid argument: {0}",
                    "KMS signature algorithm "
                            + signatureAlgorithm.name()
                            + " does not match certificate public key algorithm "
                            + publicKeyAlgorithm);
        }
    }

    abstract class VisibleCreateSignature extends CreateSignatureBase {
        public VisibleCreateSignature(KeyStore keystore, char[] pin)
                throws KeyStoreException,
                        UnrecoverableKeyException,
                        NoSuchAlgorithmException,
                        IOException,
                        CertificateException {
            super(keystore, pin);
        }

        public VisibleCreateSignature(Certificate[] certificateChain)
                throws IOException, CertificateException {
            super(certificateChain);
        }

        private byte[] loadLogo() throws IOException {
            ClassPathResource resource = new ClassPathResource("static/images/signature.png");
            try (InputStream is = resource.getInputStream()) {
                return is.readAllBytes();
            } catch (IOException e) {
                log.error("Failed to load image signature file");
                throw e;
            }
        }

        public InputStream createVisibleSignature(
                PDDocument srcDoc,
                PDSignature signature,
                Integer pageNumber,
                Boolean showLogo,
                byte[] customImage,
                String customText,
                PDSignatureField targetField)
                throws IOException {
            // modified from org.apache.pdfbox.examples.signature.CreateVisibleSignature2
            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage(srcDoc.getPage(pageNumber).getMediaBox());
                doc.addPage(page);
                PDAcroForm acroForm = new PDAcroForm(doc);
                doc.getDocumentCatalog().setAcroForm(acroForm);
                PDSignatureField signatureField = new PDSignatureField(acroForm);
                PDAnnotationWidget widget = signatureField.getWidgets().get(0);
                List<PDField> acroFormFields = acroForm.getFields();
                acroForm.setSignaturesExist(true);
                acroForm.setAppendOnly(true);
                acroForm.getCOSObject().setDirect(true);
                acroFormFields.add(signatureField);

                PDRectangle targetRectangle =
                        targetField == null
                                ? null
                                : targetField.getWidgets().getFirst().getRectangle();
                PDRectangle rect =
                        targetRectangle == null
                                ? new PDRectangle(0, 0, 200, 50)
                                : new PDRectangle(
                                        0,
                                        0,
                                        targetRectangle.getWidth(),
                                        targetRectangle.getHeight());

                widget.setRectangle(rect);

                // from PDVisualSigBuilder.createHolderForm()
                PDStream stream = new PDStream(doc);
                PDFormXObject form = new PDFormXObject(stream);
                PDResources res = new PDResources();
                form.setResources(res);
                form.setFormType(1);
                PDRectangle bbox = new PDRectangle(rect.getWidth(), rect.getHeight());
                float height = bbox.getHeight();
                form.setBBox(bbox);
                PDFont font = new PDType1Font(FontName.TIMES_BOLD);

                // from PDVisualSigBuilder.createAppearanceDictionary()
                PDAppearanceDictionary appearance = new PDAppearanceDictionary();
                appearance.getCOSObject().setDirect(true);
                PDAppearanceStream appearanceStream = new PDAppearanceStream(form.getCOSObject());
                appearance.setNormalAppearance(appearanceStream);
                widget.setAppearance(appearance);

                try (PDPageContentStream cs = new PDPageContentStream(doc, appearanceStream)) {
                    if (Boolean.TRUE.equals(showLogo) || customImage != null) {
                        cs.saveGraphicsState();
                        PDExtendedGraphicsState extState = new PDExtendedGraphicsState();
                        extState.setBlendMode(BlendMode.MULTIPLY);
                        extState.setNonStrokingAlphaConstant(0.5f);
                        cs.setGraphicsStateParameters(extState);
                        cs.transform(Matrix.getScaleInstance(0.08f, 0.08f));
                        PDImageXObject img =
                                PDImageXObject.createFromByteArray(
                                        doc,
                                        customImage == null ? loadLogo() : customImage,
                                        "signature-appearance");
                        cs.drawImage(img, 100, 0);
                        cs.restoreGraphicsState();
                    }

                    // show text
                    float fontSize = 10;
                    float leading = fontSize * 1.5f;
                    cs.beginText();
                    cs.setFont(font, fontSize);
                    cs.setNonStrokingColor(Color.black);
                    cs.newLineAtOffset(fontSize, height - leading);
                    cs.setLeading(leading);

                    X509Certificate cert = (X509Certificate) getCertificateChain()[0];

                    // https://stackoverflow.com/questions/2914521/
                    String signerName = getVisibleSignerName(cert, signature);

                    String date = signature.getSignDate().getTime().toString();
                    String reason = signature.getReason();

                    cs.showText(
                            StringUtils.isBlank(customText)
                                    ? "Signed by " + signerName
                                    : customText);
                    cs.newLine();
                    cs.showText(date);
                    cs.newLine();
                    cs.showText(reason);

                    cs.endText();
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                doc.save(baos);
                return new ByteArrayInputStream(baos.toByteArray());
            }
        }

        private String getVisibleSignerName(X509Certificate cert, PDSignature signature) {
            X500Name x500Name = new X500Name(cert.getSubjectX500Principal().getName());
            RDN[] commonNames = x500Name.getRDNs(BCStyle.CN);
            if (commonNames.length > 0) {
                return IETFUtils.valueToString(commonNames[0].getFirst().getValue());
            }
            if (!StringUtils.isBlank(signature.getName())) {
                return signature.getName();
            }
            String subject = cert.getSubjectX500Principal().getName();
            return StringUtils.isBlank(subject) ? "Unknown signer" : subject;
        }
    }

    class CreateSignature extends VisibleCreateSignature {

        public CreateSignature(KeyStore keystore, char[] pin)
                throws KeyStoreException,
                        UnrecoverableKeyException,
                        NoSuchAlgorithmException,
                        IOException,
                        CertificateException {
            super(keystore, pin);
        }
    }

    class KmsCreateSignature extends VisibleCreateSignature {
        private final KmsSignatureService kmsSignatureService;
        private final SignerProvider signerProvider;
        private final String keyId;
        private final KmsSignatureAlgorithm signatureAlgorithm;

        public KmsCreateSignature(
                Certificate[] certificateChain,
                KmsSignatureService kmsSignatureService,
                SignerProvider signerProvider,
                String keyId,
                KmsSignatureAlgorithm signatureAlgorithm)
                throws IOException, CertificateException {
            super(certificateChain);
            this.kmsSignatureService = kmsSignatureService;
            this.signerProvider = signerProvider;
            this.keyId = keyId;
            this.signatureAlgorithm = signatureAlgorithm;
        }

        @Override
        public byte[] sign(InputStream content) throws IOException {
            try {
                CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
                X509Certificate cert = (X509Certificate) getCertificateChain()[0];
                ContentSigner signer =
                        new DigestKmsContentSigner(
                                kmsSignatureService, signerProvider, keyId, signatureAlgorithm);
                JcaSignerInfoGeneratorBuilder signerInfoBuilder =
                        new JcaSignerInfoGeneratorBuilder(
                                new JcaDigestCalculatorProviderBuilder().build());
                signerInfoBuilder.setSignedAttributeGenerator(
                        createCadesSignedAttributeGenerator(cert));
                gen.addSignerInfoGenerator(signerInfoBuilder.build(signer, cert));
                gen.addCertificates(new JcaCertStore(Arrays.asList(getCertificateChain())));
                CMSTypedData msg = new InputStreamCmsTypedData(content);
                CMSSignedData signedData = gen.generate(msg, false);
                return addTimestampIfConfigured(signedData).getEncoded();
            } catch (GeneralSecurityException
                    | CMSException
                    | OperatorCreationException
                    | java.net.URISyntaxException e) {
                throw new IOException(e);
            }
        }
    }

    private static class DigestKmsContentSigner implements ContentSigner {
        private final KmsSignatureService kmsSignatureService;
        private final SignerProvider signerProvider;
        private final String keyId;
        private final KmsSignatureAlgorithm signatureAlgorithm;
        private final AlgorithmIdentifier algorithmIdentifier;
        private final ByteArrayOutputStream contentToSign = new ByteArrayOutputStream();

        private DigestKmsContentSigner(
                KmsSignatureService kmsSignatureService,
                SignerProvider signerProvider,
                String keyId,
                KmsSignatureAlgorithm signatureAlgorithm) {
            this.kmsSignatureService = kmsSignatureService;
            this.signerProvider = signerProvider;
            this.keyId = keyId;
            this.signatureAlgorithm = signatureAlgorithm;
            this.algorithmIdentifier =
                    new DefaultSignatureAlgorithmIdentifierFinder()
                            .find(signatureAlgorithm.getCmsAlgorithmName());
        }

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            return algorithmIdentifier;
        }

        @Override
        public OutputStream getOutputStream() {
            return contentToSign;
        }

        @Override
        public byte[] getSignature() {
            try {
                byte[] digest =
                        MessageDigest.getInstance(signatureAlgorithm.getDigestAlgorithm())
                                .digest(contentToSign.toByteArray());
                return kmsSignatureService.signDigest(
                        signerProvider, new KmsSigningRequest(keyId, signatureAlgorithm, digest));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Failed to sign digest with KMS", e);
            } catch (GeneralSecurityException | IOException e) {
                throw new IllegalStateException("Failed to sign digest with KMS", e);
            }
        }
    }

    private static class InputStreamCmsTypedData implements CMSTypedData {
        private final InputStream inputStream;

        private InputStreamCmsTypedData(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public Object getContent() {
            return inputStream;
        }

        @Override
        public void write(OutputStream out) throws IOException, CMSException {
            inputStream.transferTo(out);
            inputStream.close();
        }

        @Override
        public ASN1ObjectIdentifier getContentType() {
            return CMSObjectIdentifiers.data;
        }
    }
}
