package stirling.software.SPDF.controller.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Calendar;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.examples.signature.CreateSignatureBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
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

import stirling.software.SPDF.model.api.security.RemoveCertSignRequest;
import stirling.software.common.service.CustomPDFDocumentFactory;

@ExtendWith(MockitoExtension.class)
class RemoveCertSignControllerTest {

    @Mock private CustomPDFDocumentFactory pdfDocumentFactory;
    @InjectMocks private RemoveCertSignController controller;

    private CreateSignatureBase signer;

    @BeforeEach
    void setUp() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = new ClassPathResource("certs/test-cert.p12").getInputStream()) {
            keyStore.load(input, "password".toCharArray());
        }
        signer = new CreateSignatureBase(keyStore, "password".toCharArray()) {};
        lenient()
                .when(pdfDocumentFactory.load(any(MultipartFile.class)))
                .thenAnswer(
                        invocation ->
                                Loader.loadPDF(
                                        ((MultipartFile) invocation.getArgument(0)).getBytes()));
    }

    @Test
    void extractsSelectedSignedRevisionWithoutLaterChanges() throws Exception {
        byte[] firstRevision = addSignature(createPdf());
        byte[] secondRevision = addSignature(firstRevision);
        RemoveCertSignRequest request = request(secondRevision);
        request.setSignatureIndex(1);

        ResponseEntity<byte[]> response = controller.removeCertSignPDF(request);

        byte[] extracted = response.getBody();
        assertNotNull(extracted);
        assertTrue(extracted.length < secondRevision.length);
        try (PDDocument document = Loader.loadPDF(extracted)) {
            assertEquals(1, document.getSignatureDictionaries().size());
            int[] byteRange = document.getSignatureDictionaries().getFirst().getByteRange();
            assertEquals(extracted.length, byteRange[2] + byteRange[3]);
        }
    }

    @Test
    void rejectsInvalidRevisionSelection() throws Exception {
        RemoveCertSignRequest request = request(addSignature(createPdf()));
        request.setSignatureIndex(2);

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> controller.removeCertSignPDF(request));

        assertTrue(error.getMessage().contains("between 1 and 1"));
    }

    @Test
    void requiresAcknowledgementBeforeFlatteningSignedFields() throws Exception {
        RemoveCertSignRequest request = request(addSignature(createPdf()));
        request.setMode("FLATTEN_SIGNATURES");

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> controller.removeCertSignPDF(request));

        assertTrue(error.getMessage().contains("explicit acknowledgement"));
    }

    private RemoveCertSignRequest request(byte[] bytes) {
        RemoveCertSignRequest request = new RemoveCertSignRequest();
        request.setFileInput(
                new MockMultipartFile(
                        "fileInput", "signed.pdf", MediaType.APPLICATION_PDF_VALUE, bytes));
        return request;
    }

    private byte[] createPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] addSignature(byte[] input) throws Exception {
        try (PDDocument document = Loader.loadPDF(input)) {
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
}
