package stirling.software.SPDF.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import stirling.software.SPDF.model.api.esign.ESignatureSignRequest;
import stirling.software.SPDF.model.signing.SigningField;
import stirling.software.SPDF.model.signing.SigningRecipient;

@Service
public class ESignaturePdfService {

    private static final Pattern IMAGE_DATA_URL =
            Pattern.compile("^data:image/(png|jpe?g);base64,(.+)$", Pattern.DOTALL);
    private static final int MAX_SIGNATURE_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final DateTimeFormatter SIGNING_DATE_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    public AppliedRevision applyRecipientFields(
            Path documentPath,
            List<SigningField> fields,
            SigningRecipient recipient,
            ESignatureSignRequest request,
            Instant signedAt)
            throws IOException {
        List<SigningField> recipientFields =
                fields.stream()
                        .filter(Objects::nonNull)
                        .filter(field -> Objects.equals(field.getRecipientId(), recipient.getId()))
                        .toList();
        if (recipientFields.isEmpty()) {
            return new AppliedRevision(0, sha256(documentPath), sha256(documentPath));
        }

        String beforeHash = sha256(documentPath);
        Path parent = documentPath.toAbsolutePath().normalize().getParent();
        Path outputPath = Files.createTempFile(parent, "document-revision-", ".pdf");
        boolean completed = false;
        try {
            try (PDDocument document = Loader.loadPDF(documentPath.toFile());
                    InputStream fontStream =
                            ESignaturePdfService.class.getResourceAsStream(
                                    "/static/fonts/NotoSans-Regular.ttf")) {
                if (fontStream == null) {
                    throw new IOException("Signing font is unavailable");
                }
                PDFont font = PDType0Font.load(document, fontStream, true);
                for (SigningField field : recipientFields) {
                    if (field.getPageIndex() < 0
                            || field.getPageIndex() >= document.getNumberOfPages()) {
                        throw badRequest("Signing field references a page that does not exist");
                    }
                    applyField(document, field, recipient, request, signedAt, font);
                }
                try (OutputStream output = Files.newOutputStream(outputPath)) {
                    document.saveIncremental(output);
                }
            }
            replaceAtomically(outputPath, documentPath);
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(outputPath);
            }
        }
        return new AppliedRevision(recipientFields.size(), beforeHash, sha256(documentPath));
    }

    private void applyField(
            PDDocument document,
            SigningField field,
            SigningRecipient recipient,
            ESignatureSignRequest request,
            Instant signedAt,
            PDFont font)
            throws IOException {
        if (field.getBounds() == null || !field.getBounds().isWithinPage()) {
            throw badRequest("Signing field bounds are invalid");
        }
        PDPage page = document.getPage(field.getPageIndex());
        PDRectangle cropBox = page.getCropBox();
        float x = cropBox.getLowerLeftX() + (float) (field.getBounds().getX() * cropBox.getWidth());
        float width = (float) (field.getBounds().getWidth() * cropBox.getWidth());
        float height = (float) (field.getBounds().getHeight() * cropBox.getHeight());
        float y =
                cropBox.getLowerLeftY()
                        + cropBox.getHeight()
                        - (float)
                                ((field.getBounds().getY() + field.getBounds().getHeight())
                                        * cropBox.getHeight());

        String value = resolveValue(field, recipient, request, signedAt);
        try (PDPageContentStream content =
                new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
            if ((field.getType() == SigningField.Type.SIGNATURE
                            || field.getType() == SigningField.Type.INITIALS)
                    && StringUtils.hasText(request.getSignatureDataUrl())) {
                drawSignatureImage(
                        document, content, request.getSignatureDataUrl(), x, y, width, height);
                field.setValue("signed");
            } else {
                drawText(content, font, value, x, y, width, height);
                field.setValue(value);
            }
        }
        field.setCompletedAt(signedAt);
    }

    private String resolveValue(
            SigningField field,
            SigningRecipient recipient,
            ESignatureSignRequest request,
            Instant signedAt) {
        Map<String, String> fieldValues =
                request.getFieldValues() == null ? Map.of() : request.getFieldValues();
        String value = fieldValues.get(field.getId());
        if (!StringUtils.hasText(value)) {
            value = field.getDefaultValue();
        }
        if (!StringUtils.hasText(value)) {
            value =
                    switch (field.getType()) {
                        case SIGNATURE, INITIALS, NAME ->
                                defaultIfBlank(request.getSignerName(), recipient.getName());
                        case EMAIL -> recipient.getEmail();
                        case DATE_SIGNED -> SIGNING_DATE_FORMAT.format(signedAt);
                        case CHECKBOX -> "false";
                        case TEXT, RADIO, DROPDOWN -> null;
                    };
        }
        if (field.getType() == SigningField.Type.CHECKBOX) {
            boolean checked = Boolean.parseBoolean(value);
            if (field.isRequired() && !checked) {
                throw badRequest("Required checkbox field was not accepted: " + field.getName());
            }
            return checked ? "X" : "";
        }
        if (field.isRequired() && !StringUtils.hasText(value)) {
            throw badRequest("Required signing field is missing: " + field.getName());
        }
        return value == null ? "" : value;
    }

    private void drawSignatureImage(
            PDDocument document,
            PDPageContentStream content,
            String dataUrl,
            float x,
            float y,
            float width,
            float height)
            throws IOException {
        Matcher matcher = IMAGE_DATA_URL.matcher(dataUrl);
        if (!matcher.matches()) {
            throw badRequest("Signature image must be a PNG or JPEG data URL");
        }
        byte[] imageBytes;
        try {
            imageBytes = Base64.getDecoder().decode(matcher.group(2));
        } catch (IllegalArgumentException e) {
            throw badRequest("Signature image data is invalid");
        }
        if (imageBytes.length == 0 || imageBytes.length > MAX_SIGNATURE_IMAGE_BYTES) {
            throw badRequest("Signature image exceeds the allowed size");
        }
        PDImageXObject image =
                PDImageXObject.createFromByteArray(document, imageBytes, "signer-signature");
        float scale = Math.min(width / image.getWidth(), height / image.getHeight());
        float drawWidth = image.getWidth() * scale;
        float drawHeight = image.getHeight() * scale;
        content.drawImage(
                image,
                x + (width - drawWidth) / 2,
                y + (height - drawHeight) / 2,
                drawWidth,
                drawHeight);
    }

    private void drawText(
            PDPageContentStream content,
            PDFont font,
            String value,
            float x,
            float y,
            float width,
            float height)
            throws IOException {
        if (!StringUtils.hasText(value)) {
            return;
        }
        float fontSize = Math.max(6, Math.min(18, height * 0.55f));
        String fitted = fitText(font, value, fontSize, Math.max(1, width - 6));
        content.saveGraphicsState();
        content.addRect(x, y, width, height);
        content.clip();
        content.beginText();
        content.setFont(font, fontSize);
        content.newLineAtOffset(x + 3, y + Math.max(2, (height - fontSize) / 2));
        content.showText(fitted);
        content.endText();
        content.restoreGraphicsState();
    }

    private String fitText(PDFont font, String value, float fontSize, float availableWidth)
            throws IOException {
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (font.getStringWidth(normalized) / 1000 * fontSize <= availableWidth) {
            return normalized;
        }
        String ellipsis = "...";
        String candidate = normalized;
        while (!candidate.isEmpty()
                && font.getStringWidth(candidate + ellipsis) / 1000 * fontSize > availableWidth) {
            candidate =
                    candidate.substring(
                            0,
                            candidate.offsetByCodePoints(
                                    0, candidate.codePointCount(0, candidate.length()) - 1));
        }
        return candidate + ellipsis;
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void replaceAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record AppliedRevision(int fieldCount, String beforeSha256, String afterSha256) {}
}
