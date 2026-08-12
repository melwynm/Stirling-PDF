package stirling.software.SPDF.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import stirling.software.SPDF.model.signing.SigningAnchor;
import stirling.software.SPDF.model.signing.SigningField;
import stirling.software.SPDF.model.signing.SigningField.NormalizedBounds;

@Service
public class SigningAnchorService {

    public List<SigningField> resolve(byte[] pdf, List<SigningAnchor> anchors) throws IOException {
        if (anchors == null || anchors.isEmpty()) return List.of();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PositionStripper stripper = new PositionStripper();
            stripper.getText(document);
            List<SigningField> fields = new ArrayList<>();
            for (SigningAnchor anchor : anchors) {
                validate(anchor);
                List<TextRun> matches = stripper.find(anchor.getText(), anchor.isMatchCase());
                if (matches.isEmpty() && anchor.isRequired()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Required signing anchor not found: " + anchor.getText());
                }
                int limit =
                        anchor.isAllOccurrences() ? matches.size() : Math.min(1, matches.size());
                for (int index = 0; index < limit; index++) {
                    fields.add(toField(anchor, matches.get(index), index));
                }
            }
            return fields;
        }
    }

    private SigningField toField(SigningAnchor anchor, TextRun match, int index) {
        SigningField field = new SigningField();
        String baseName =
                StringUtils.hasText(anchor.getName()) ? anchor.getName().trim() : "anchor-field";
        field.setName(index == 0 ? baseName : baseName + "-" + (index + 1));
        field.setLabel(StringUtils.hasText(anchor.getLabel()) ? anchor.getLabel() : baseName);
        field.setRecipientId(anchor.getRecipientId());
        field.setType(anchor.getType());
        field.setRequired(anchor.isRequired());
        field.setPageIndex(match.pageIndex());
        NormalizedBounds bounds = new NormalizedBounds();
        bounds.setX(clamp(match.x() + anchor.getOffsetX(), 0, 1 - anchor.getWidth()));
        bounds.setY(clamp(match.y() + anchor.getOffsetY(), 0, 1 - anchor.getHeight()));
        bounds.setWidth(anchor.getWidth());
        bounds.setHeight(anchor.getHeight());
        field.setBounds(bounds);
        field.getMetadata().put("anchorText", anchor.getText());
        return field;
    }

    private void validate(SigningAnchor anchor) {
        if (anchor == null || !StringUtils.hasText(anchor.getText())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Signing anchor text is required");
        }
        if (anchor.getWidth() <= 0
                || anchor.getWidth() > 1
                || anchor.getHeight() <= 0
                || anchor.getHeight() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Signing anchor dimensions must be between 0 and 1");
        }
    }

    private double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static class PositionStripper extends PDFTextStripper {
        private final List<TextRun> runs = new ArrayList<>();

        PositionStripper() throws IOException {
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (positions.isEmpty()) return;
            float pageWidth = getCurrentPage().getCropBox().getWidth();
            float pageHeight = getCurrentPage().getCropBox().getHeight();
            runs.add(
                    new TextRun(
                            text,
                            getCurrentPageNo() - 1,
                            positions.getFirst().getXDirAdj() / pageWidth,
                            positions.getFirst().getYDirAdj() / pageHeight));
        }

        List<TextRun> find(String needle, boolean matchCase) {
            String expected = matchCase ? needle : needle.toLowerCase(Locale.ROOT);
            return runs.stream()
                    .filter(
                            run ->
                                    (matchCase ? run.text() : run.text().toLowerCase(Locale.ROOT))
                                            .contains(expected))
                    .toList();
        }
    }

    private record TextRun(String text, int pageIndex, double x, double y) {}
}
