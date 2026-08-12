package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.SPDF.model.api.esign.ESignatureSignRequest;
import stirling.software.SPDF.model.signing.SigningField;
import stirling.software.SPDF.model.signing.SigningField.NormalizedBounds;
import stirling.software.SPDF.model.signing.SigningRecipient;

class ESignaturePdfServiceTest {

    @TempDir Path tempDir;

    @Test
    void appliesRecipientFieldsAsAnIncrementalPdfRevision() throws Exception {
        Path pdfPath = tempDir.resolve("document.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.LETTER));
            document.save(pdfPath.toFile());
        }
        byte[] original = Files.readAllBytes(pdfPath);

        SigningRecipient recipient = new SigningRecipient();
        recipient.setId("recipient-1");
        recipient.setName("Ada Lovelace");
        recipient.setEmail("ada@example.com");

        SigningField signature = field("signature-1", SigningField.Type.SIGNATURE, 0.1, 0.7);
        SigningField text = field("text-1", SigningField.Type.TEXT, 0.1, 0.82);
        ESignatureSignRequest request = new ESignatureSignRequest();
        request.setSignerName("Ada Lovelace");
        request.setFieldValues(Map.of("text-1", "Approved for release"));
        Instant signedAt = Instant.parse("2026-07-11T12:00:00Z");

        ESignaturePdfService.AppliedRevision result =
                new ESignaturePdfService()
                        .applyRecipientFields(
                                pdfPath, List.of(signature, text), recipient, request, signedAt);

        byte[] revised = Files.readAllBytes(pdfPath);
        assertEquals(2, result.fieldCount());
        assertNotEquals(result.beforeSha256(), result.afterSha256());
        assertTrue(revised.length > original.length);
        assertArrayEquals(original, Arrays.copyOf(revised, original.length));
        assertEquals("Ada Lovelace", signature.getValue());
        assertEquals(signedAt, signature.getCompletedAt());

        try (PDDocument revisedDocument = Loader.loadPDF(pdfPath.toFile())) {
            String textContent = new PDFTextStripper().getText(revisedDocument);
            assertTrue(textContent.contains("Ada Lovelace"));
            assertTrue(textContent.contains("Approved for release"));
        }
    }

    private SigningField field(
            String id, SigningField.Type type, double normalizedX, double normalizedY) {
        NormalizedBounds bounds = new NormalizedBounds();
        bounds.setX(normalizedX);
        bounds.setY(normalizedY);
        bounds.setWidth(0.35);
        bounds.setHeight(0.08);

        SigningField field = new SigningField();
        field.setId(id);
        field.setRecipientId("recipient-1");
        field.setName(id);
        field.setLabel(id);
        field.setType(type);
        field.setPageIndex(0);
        field.setBounds(bounds);
        return field;
    }
}
