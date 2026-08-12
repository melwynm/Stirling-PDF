package stirling.software.SPDF.controller.api.security;

import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.config.swagger.StandardPdfResponse;
import stirling.software.SPDF.model.api.security.RemoveCertSignRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.SecurityApi;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.WebResponseUtils;

@SecurityApi
@RequiredArgsConstructor
public class RemoveCertSignController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;

    @AutoJobPostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, value = "/remove-cert-sign")
    @StandardPdfResponse
    @Operation(
            summary = "Extract a signed PDF revision or rewrite signature fields",
            description =
                    "Safely extracts an exact signed revision by default. It can also clear empty"
                            + " signature fields or, with explicit acknowledgement, flatten signed"
                            + " fields and invalidate their cryptographic evidence."
                            + " Input:PDF, Output:PDF Type:SISO")
    public ResponseEntity<byte[]> removeCertSignPDF(@ModelAttribute RemoveCertSignRequest request)
            throws Exception {
        MultipartFile pdf = request.getFileInput();
        String mode =
                request.getMode() == null
                        ? "EXTRACT_REVISION"
                        : request.getMode().trim().toUpperCase(Locale.ROOT);

        if ("EXTRACT_REVISION".equals(mode)) {
            return extractRevision(pdf, request.getSignatureIndex());
        }
        if (!List.of("CLEAR_UNSIGNED_FIELDS", "FLATTEN_SIGNATURES").contains(mode)) {
            throw new IllegalArgumentException("Unknown signature removal mode: " + mode);
        }
        if ("FLATTEN_SIGNATURES".equals(mode)
                && !Boolean.TRUE.equals(request.getAcknowledgeDestructive())) {
            throw new IllegalArgumentException(
                    "Destructive signature flattening requires explicit acknowledgement");
        }

        // Load the PDF document with proper resource management
        try (PDDocument document = pdfDocumentFactory.load(pdf)) {

            // Get the document catalog
            PDDocumentCatalog catalog = document.getDocumentCatalog();

            // Get the AcroForm
            PDAcroForm acroForm = catalog.getAcroForm();
            if (acroForm != null) {
                // Remove signature fields safely
                List<PDField> fieldsToRemove =
                        acroForm.getFields().stream()
                                .filter(
                                        field ->
                                                field instanceof PDSignatureField signatureField
                                                        && ("FLATTEN_SIGNATURES".equals(mode)
                                                                || signatureField.getSignature()
                                                                        == null))
                                .toList();

                if (!fieldsToRemove.isEmpty()) {
                    acroForm.flatten(fieldsToRemove, false);
                }
            }
            // Return the modified PDF as a response
            return WebResponseUtils.pdfDocToWebResponse(
                    document,
                    GeneralUtils.generateFilename(
                            pdf.getOriginalFilename(),
                            "FLATTEN_SIGNATURES".equals(mode)
                                    ? "_signatures-flattened.pdf"
                                    : "_unsigned-fields-cleared.pdf"));
        }
    }

    private ResponseEntity<byte[]> extractRevision(MultipartFile pdf, Integer requestedIndex)
            throws Exception {
        byte[] inputBytes = pdf.getBytes();
        try (PDDocument document = pdfDocumentFactory.load(pdf)) {
            List<org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature> signatures =
                    document.getSignatureDictionaries();
            if (signatures.isEmpty()) {
                throw new IllegalArgumentException("PDF does not contain a signed revision");
            }
            int signatureIndex = requestedIndex == null ? signatures.size() : requestedIndex;
            if (signatureIndex < 1 || signatureIndex > signatures.size()) {
                throw new IllegalArgumentException(
                        "Signature revision index must be between 1 and " + signatures.size());
            }
            var signature = signatures.get(signatureIndex - 1);
            int[] byteRange = signature.getByteRange();
            if (byteRange == null || byteRange.length != 4) {
                throw new IllegalArgumentException("Signature does not contain a valid byte range");
            }
            long revisionLength = (long) byteRange[2] + byteRange[3];
            if (revisionLength <= 0 || revisionLength > inputBytes.length) {
                throw new IllegalArgumentException("Signed revision byte range is invalid");
            }
            byte[] revision = java.util.Arrays.copyOf(inputBytes, (int) revisionLength);
            try (PDDocument ignored = org.apache.pdfbox.Loader.loadPDF(revision)) {
                // Confirm that the extracted revision is independently readable.
            }
            return WebResponseUtils.bytesToWebResponse(
                    revision,
                    GeneralUtils.generateFilename(
                            pdf.getOriginalFilename(),
                            "_signed-revision-" + signatureIndex + ".pdf"));
        }
    }
}
