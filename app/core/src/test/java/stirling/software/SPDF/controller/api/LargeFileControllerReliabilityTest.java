package stirling.software.SPDF.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import stirling.software.SPDF.model.api.general.RotatePDFRequest;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.service.PdfMetadataService;

@Tag("large-file-reliability")
class LargeFileControllerReliabilityTest {

    private RotationController rotationController;
    private byte[] basePdfBytes;

    @BeforeEach
    void setUp() throws IOException {
        rotationController =
                new RotationController(
                        new CustomPDFDocumentFactory(mock(PdfMetadataService.class)));
        basePdfBytes = createBasePdf();
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 50, 100})
    void rotatePdfReturnsValidPdfForGeneratedLargeInputs(int sizeMB) throws Exception {
        byte[] pdfBytes = inflatePdf(basePdfBytes, sizeMB);
        MockMultipartFile multipartFile =
                new MockMultipartFile(
                        "fileInput",
                        "large-" + sizeMB + "mb.pdf",
                        MediaType.APPLICATION_PDF_VALUE,
                        pdfBytes);
        RotatePDFRequest request = new RotatePDFRequest();
        request.setFileInput(multipartFile);
        request.setAngle(90);

        ResponseEntity<byte[]> response = rotationController.rotatePDF(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        try (PDDocument document = Loader.loadPDF(response.getBody())) {
            assertEquals(1, document.getNumberOfPages());
            assertEquals(90, document.getPage(0).getRotation());
        }
    }

    private static byte[] inflatePdf(byte[] input, int sizeInMB) throws IOException {
        try (PDDocument doc = Loader.loadPDF(input)) {
            byte[] largeData = new byte[sizeInMB * 1024 * 1024];
            Arrays.fill(largeData, (byte) 'A');

            PDStream stream = new PDStream(doc, new ByteArrayInputStream(largeData));
            stream.getCOSObject().setItem(COSName.TYPE, COSName.XOBJECT);
            stream.getCOSObject().setItem(COSName.SUBTYPE, COSName.IMAGE);

            doc.getDocumentCatalog()
                    .getCOSObject()
                    .setItem(COSName.getPDFName("DummyBigStream"), stream.getCOSObject());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] createBasePdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
