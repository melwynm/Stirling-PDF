package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import stirling.software.SPDF.model.api.esign.ESignatureCreateRequest;
import stirling.software.SPDF.model.api.esign.ESignatureRecipientRequest;
import stirling.software.SPDF.model.api.esign.ESignatureRequestView;
import stirling.software.SPDF.model.api.esign.ESignatureTemplateCreateRequest;
import stirling.software.SPDF.model.api.esign.ESignatureTemplateInstantiateRequest;
import stirling.software.SPDF.model.esign.ESignatureTemplate;
import stirling.software.SPDF.model.signing.SigningField;
import stirling.software.SPDF.model.signing.SigningField.NormalizedBounds;
import stirling.software.SPDF.model.signing.SigningRecipient.Method;
import stirling.software.SPDF.service.ESignatureWorkflowService.ActorContext;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ESignatureTemplateServiceTest {

    @TempDir Path tempDir;

    private ESignatureTemplateService templateService;
    private ActorContext owner;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = JsonMapper.builder().build();
        ESignatureWorkflowService workflowService =
                new ESignatureWorkflowService(mapper, tempDir.resolve("requests"));
        templateService =
                new ESignatureTemplateService(
                        mapper, workflowService, tempDir.resolve("templates"));
        owner = new ActorContext("Owner", "owner@example.com", "127.0.0.1", "JUnit", "owner-1");
    }

    @Test
    void createsListsAndInstantiatesOwnedTemplate() throws Exception {
        ESignatureTemplate template = templateService.create(pdfFile(), templateRequest(), owner);

        assertEquals("Employment agreement", template.getName());
        assertEquals(1, templateService.list(owner).size());
        assertEquals(0, templateService.list(otherActor()).size());

        ESignatureTemplateInstantiateRequest instantiate =
                new ESignatureTemplateInstantiateRequest();
        ESignatureCreateRequest overrides = new ESignatureCreateRequest();
        overrides.setTitle("Agreement for Ada");
        overrides.setRecipients(List.of(recipient("Ada", "ada@example.com")));
        instantiate.setOverrides(overrides);

        ESignatureRequestView created =
                templateService.instantiate(template.getId(), instantiate, owner);

        assertEquals("Agreement for Ada", created.getTitle());
        assertEquals("ada@example.com", created.getRecipients().getFirst().getEmail());
        assertEquals(1, created.getFields().size());
        assertFalse(created.getId().equals(template.getId()));
    }

    @Test
    void hidesTemplatesFromOtherOwnersAndDeletesSource() throws Exception {
        ESignatureTemplate template = templateService.create(pdfFile(), templateRequest(), owner);

        ResponseStatusException hidden =
                assertThrows(
                        ResponseStatusException.class,
                        () -> templateService.get(template.getId(), otherActor()));
        assertEquals(HttpStatus.NOT_FOUND, hidden.getStatusCode());

        templateService.delete(template.getId(), owner);
        assertFalse(Files.exists(tempDir.resolve("templates").resolve(template.getId())));
    }

    @Test
    void refusesToPersistReusableAccessCodes() throws Exception {
        ESignatureTemplateCreateRequest request = templateRequest();
        request.getDefaults()
                .getRecipients()
                .getFirst()
                .setAuthenticationMethod(Method.ACCESS_CODE);
        request.getDefaults().getRecipients().getFirst().setAccessCode("secret-code");

        ResponseStatusException error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> templateService.create(pdfFile(), request, owner));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertFalse(Files.exists(tempDir.resolve("templates")));
    }

    private ESignatureTemplateCreateRequest templateRequest() {
        ESignatureCreateRequest defaults = new ESignatureCreateRequest();
        defaults.setTitle("Employment agreement");
        defaults.setRecipients(List.of(recipient("Candidate", "candidate@example.com")));
        defaults.setFields(List.of(signatureField()));
        ESignatureTemplateCreateRequest request = new ESignatureTemplateCreateRequest();
        request.setName("Employment agreement");
        request.setDescription("Standard candidate agreement");
        request.setDefaults(defaults);
        return request;
    }

    private ESignatureRecipientRequest recipient(String name, String email) {
        ESignatureRecipientRequest recipient = new ESignatureRecipientRequest();
        recipient.setId("candidate");
        recipient.setName(name);
        recipient.setEmail(email);
        return recipient;
    }

    private SigningField signatureField() {
        SigningField field = new SigningField();
        field.setId("signature");
        field.setName("candidate-signature");
        field.setLabel("Signature");
        field.setRecipientId("candidate");
        NormalizedBounds bounds = new NormalizedBounds();
        bounds.setX(0.55);
        bounds.setY(0.75);
        bounds.setWidth(0.35);
        bounds.setHeight(0.1);
        field.setBounds(bounds);
        return field;
    }

    private MockMultipartFile pdfFile() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return new MockMultipartFile(
                    "file", "agreement.pdf", "application/pdf", output.toByteArray());
        }
    }

    private ActorContext otherActor() {
        return new ActorContext("Other", "other@example.com", "127.0.0.2", "JUnit", "owner-2");
    }
}
