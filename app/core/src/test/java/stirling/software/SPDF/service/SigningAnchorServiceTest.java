package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import stirling.software.SPDF.model.signing.SigningAnchor;
import stirling.software.SPDF.model.signing.SigningField;

class SigningAnchorServiceTest {

    private final SigningAnchorService service = new SigningAnchorService();

    @Test
    void resolvesAnchorToNormalizedFieldOnMatchingPage() throws Exception {
        SigningAnchor anchor = anchor("SIGN HERE");
        anchor.setRecipientId("recipient-1");
        anchor.setName("signature");
        anchor.setOffsetX(0.02);

        List<SigningField> fields = service.resolve(pdfWithText(), List.of(anchor));

        assertEquals(1, fields.size());
        SigningField field = fields.getFirst();
        assertEquals(1, field.getPageIndex());
        assertEquals("recipient-1", field.getRecipientId());
        assertEquals("SIGN HERE", field.getMetadata().get("anchorText"));
        assertTrue(field.getBounds().isWithinPage());
    }

    @Test
    void requiredMissingAnchorFailsClearly() throws Exception {
        ResponseStatusException error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.resolve(pdfWithText(), List.of(anchor("NOT PRESENT"))));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("NOT PRESENT"));
    }

    @Test
    void optionalMissingAnchorCreatesNoField() throws Exception {
        SigningAnchor anchor = anchor("NOT PRESENT");
        anchor.setRequired(false);

        assertTrue(service.resolve(pdfWithText(), List.of(anchor)).isEmpty());
    }

    private SigningAnchor anchor(String text) {
        SigningAnchor anchor = new SigningAnchor();
        anchor.setText(text);
        return anchor;
    }

    private byte[] pdfWithText() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDPage target = new PDPage();
            document.addPage(target);
            try (PDPageContentStream content = new PDPageContentStream(document, target)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(120, 180);
                content.showText("Please SIGN HERE to accept");
                content.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }
}
