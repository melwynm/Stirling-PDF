package stirling.software.common.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.InputStream;

import org.apache.pdfbox.rendering.ImageType;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import stirling.software.common.service.SpyPDFDocumentFactory.StrategyType;
import stirling.software.common.util.PdfUtils;

class PdfUtilsConvertFromPdfTest {

    @Test
    void convertFromMultipartUsesAdaptiveFactoryLoadPath() throws Exception {
        byte[] pdfBytes;
        try (InputStream is = getClass().getResourceAsStream("/example.pdf")) {
            assertNotNull(is, "example.pdf must be present in src/test/resources");
            pdfBytes = is.readAllBytes();
        }

        MockMultipartFile file =
                new MockMultipartFile(
                        "fileInput", "example.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        SpyPDFDocumentFactory factory = new SpyPDFDocumentFactory(mock(PdfMetadataService.class));

        byte[] zipBytes =
                PdfUtils.convertFromPdf(
                        factory, file, "PNG", ImageType.RGB, false, 72, "example", true);

        assertTrue(zipBytes.length > 0);
        assertArrayEquals(new byte[] {'P', 'K'}, new byte[] {zipBytes[0], zipBytes[1]});
        assertEquals(StrategyType.MEMORY_ONLY, factory.lastStrategyUsed);
    }
}
