package stirling.software.SPDF.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.esign.ESignatureBulkTemplateItemResult;
import stirling.software.SPDF.model.api.esign.ESignatureBulkTemplateRequest;
import stirling.software.SPDF.model.api.esign.ESignatureBulkTemplateResponse;
import stirling.software.SPDF.model.api.esign.ESignatureCreateRequest;
import stirling.software.SPDF.model.api.esign.ESignatureRequestView;
import stirling.software.SPDF.model.api.esign.ESignatureSendRequest;
import stirling.software.SPDF.model.api.esign.ESignatureTemplateCreateRequest;
import stirling.software.SPDF.model.api.esign.ESignatureTemplateInstantiateRequest;
import stirling.software.SPDF.model.esign.ESignatureTemplate;
import stirling.software.SPDF.model.signing.SigningRecipient.Method;
import stirling.software.SPDF.service.ESignatureWorkflowService.ActorContext;
import stirling.software.common.configuration.InstallationPathConfig;

import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class ESignatureTemplateService {

    private static final String METADATA_FILE = "metadata.json";
    private static final String DOCUMENT_FILE = "document.pdf";
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9-]+$");
    private static final int MAX_BULK_ITEMS = 500;

    private final ObjectMapper objectMapper;
    private final ESignatureWorkflowService workflowService;
    private final Path templatesPath;

    @Autowired
    public ESignatureTemplateService(
            ObjectMapper objectMapper, ESignatureWorkflowService workflowService) {
        this(
                objectMapper,
                workflowService,
                Paths.get(
                        InstallationPathConfig.getCustomFilesPath(),
                        "e-signature-workflows",
                        "templates"));
    }

    ESignatureTemplateService(
            ObjectMapper objectMapper,
            ESignatureWorkflowService workflowService,
            Path templatesPath) {
        this.objectMapper = objectMapper;
        this.workflowService = workflowService;
        this.templatesPath = templatesPath.toAbsolutePath().normalize();
    }

    public ESignatureTemplate create(
            MultipartFile file, ESignatureTemplateCreateRequest request, ActorContext actor)
            throws IOException {
        if (request == null || !StringUtils.hasText(request.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Template name is required");
        }
        ESignatureCreateRequest defaults =
                request.getDefaults() == null
                        ? new ESignatureCreateRequest()
                        : copyRequest(request.getDefaults());
        boolean containsAccessCode =
                defaults.getRecipients().stream()
                        .anyMatch(
                                recipient ->
                                        recipient.getAuthenticationMethod() == Method.ACCESS_CODE);
        if (containsAccessCode) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Access-code recipients must be supplied when instantiating a template");
        }
        workflowService.validateDraft(file, defaults);

        Instant now = Instant.now();
        ESignatureTemplate template = new ESignatureTemplate();
        template.setId(UUID.randomUUID().toString());
        template.setOwnerId(principalId(actor));
        template.setName(request.getName().trim());
        template.setDescription(request.getDescription());
        template.setOriginalFilename(
                workflowService.sanitizeWorkflowFilename(file.getOriginalFilename()));
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template.setDefaults(defaults);

        Path path = templatePath(template.getId());
        Files.createDirectories(path);
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, path.resolve(DOCUMENT_FILE), StandardCopyOption.REPLACE_EXISTING);
        }
        save(template);
        return copyTemplate(template);
    }

    public List<ESignatureTemplate> list(ActorContext actor) throws IOException {
        Files.createDirectories(templatesPath);
        try (var stream = Files.list(templatesPath)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.resolve(METADATA_FILE))
                    .filter(Files::exists)
                    .map(this::tryRead)
                    .filter(Objects::nonNull)
                    .filter(template -> Objects.equals(template.getOwnerId(), principalId(actor)))
                    .sorted(Comparator.comparing(ESignatureTemplate::getUpdatedAt).reversed())
                    .map(this::copyTemplate)
                    .toList();
        }
    }

    public ESignatureTemplate get(String templateId, ActorContext actor) throws IOException {
        ESignatureTemplate template = load(templateId);
        ensureOwned(template, actor);
        return copyTemplate(template);
    }

    public ESignatureRequestView instantiate(
            String templateId,
            ESignatureTemplateInstantiateRequest instantiateRequest,
            ActorContext actor)
            throws IOException {
        ESignatureTemplate template = load(templateId);
        ensureOwned(template, actor);
        ESignatureCreateRequest request =
                merge(
                        template.getDefaults(),
                        instantiateRequest == null ? null : instantiateRequest.getOverrides());
        if (instantiateRequest != null && instantiateRequest.getSigningOrder() != null) {
            request.setSigningOrder(instantiateRequest.getSigningOrder());
        }
        if (instantiateRequest != null && instantiateRequest.getRemindersEnabled() != null) {
            request.setRemindersEnabled(instantiateRequest.getRemindersEnabled());
        }
        Path document = templatePath(templateId).resolve(template.getDocumentFileName());
        return workflowService.createRequest(
                new StoredMultipartFile(template.getOriginalFilename(), document), request, actor);
    }

    public void delete(String templateId, ActorContext actor) throws IOException {
        ESignatureTemplate template = load(templateId);
        ensureOwned(template, actor);
        Path path = templatePath(templateId);
        try (var files = Files.walk(path)) {
            for (Path candidate : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    public ESignatureBulkTemplateResponse instantiateBulk(
            String templateId, ESignatureBulkTemplateRequest bulkRequest, ActorContext actor)
            throws IOException {
        if (bulkRequest == null
                || bulkRequest.getItems() == null
                || bulkRequest.getItems().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "At least one bulk template item is required");
        }
        if (bulkRequest.getItems().size() > MAX_BULK_ITEMS) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Bulk template requests are limited to " + MAX_BULK_ITEMS + " items");
        }
        // Resolve ownership before processing any rows.
        ensureOwned(load(templateId), actor);

        ESignatureBulkTemplateResponse response = new ESignatureBulkTemplateResponse();
        response.setRequested(bulkRequest.getItems().size());
        for (int index = 0; index < bulkRequest.getItems().size(); index++) {
            ESignatureBulkTemplateItemResult result = new ESignatureBulkTemplateItemResult();
            result.setIndex(index);
            try {
                ESignatureRequestView request =
                        instantiate(templateId, bulkRequest.getItems().get(index), actor);
                result.setRequest(request);
                if (bulkRequest.isSendImmediately()) {
                    ESignatureSendRequest send = new ESignatureSendRequest();
                    send.setPublicBaseUrl(bulkRequest.getPublicBaseUrl());
                    result.setAction(workflowService.sendRequest(request.getId(), send, actor));
                    result.setRequest(result.getAction().getRequest());
                }
                result.setSuccess(true);
                response.setSucceeded(response.getSucceeded() + 1);
            } catch (ResponseStatusException | IllegalArgumentException e) {
                result.setError(e.getMessage());
                response.setFailed(response.getFailed() + 1);
            }
            response.getResults().add(result);
        }
        return response;
    }

    private ESignatureCreateRequest merge(
            ESignatureCreateRequest defaults, ESignatureCreateRequest overrides) {
        if (overrides == null) {
            return copyRequest(defaults);
        }
        ESignatureCreateRequest merged = copyRequest(defaults);
        if (StringUtils.hasText(overrides.getTitle())) merged.setTitle(overrides.getTitle());
        if (overrides.getMessage() != null) merged.setMessage(overrides.getMessage());
        if (StringUtils.hasText(overrides.getRequesterName()))
            merged.setRequesterName(overrides.getRequesterName());
        if (StringUtils.hasText(overrides.getRequesterEmail()))
            merged.setRequesterEmail(overrides.getRequesterEmail());
        if (StringUtils.hasText(overrides.getPublicBaseUrl()))
            merged.setPublicBaseUrl(overrides.getPublicBaseUrl());
        if (overrides.getCallbackUrl() != null) merged.setCallbackUrl(overrides.getCallbackUrl());
        if (overrides.getExpiresAt() != null) merged.setExpiresAt(overrides.getExpiresAt());
        if (overrides.getReminderIntervalHours() != null)
            merged.setReminderIntervalHours(overrides.getReminderIntervalHours());
        if (overrides.getRecipients() != null && !overrides.getRecipients().isEmpty())
            merged.setRecipients(overrides.getRecipients());
        if (overrides.getFields() != null && !overrides.getFields().isEmpty())
            merged.setFields(overrides.getFields());
        return merged;
    }

    private ESignatureCreateRequest copyRequest(ESignatureCreateRequest request) {
        return objectMapper.convertValue(request, ESignatureCreateRequest.class);
    }

    private ESignatureTemplate copyTemplate(ESignatureTemplate template) {
        return objectMapper.convertValue(template, ESignatureTemplate.class);
    }

    private void save(ESignatureTemplate template) throws IOException {
        Path path = templatePath(template.getId());
        Files.createDirectories(path);
        Path temporary = Files.createTempFile(path, "metadata-", ".tmp");
        Files.write(temporary, objectMapper.writeValueAsBytes(template));
        try {
            Files.move(
                    temporary,
                    path.resolve(METADATA_FILE),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path.resolve(METADATA_FILE), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private ESignatureTemplate load(String templateId) throws IOException {
        validateId(templateId);
        Path metadata = templatePath(templateId).resolve(METADATA_FILE);
        if (!Files.exists(metadata)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "E-signature template not found");
        }
        return objectMapper.readValue(metadata.toFile(), ESignatureTemplate.class);
    }

    private ESignatureTemplate tryRead(Path metadata) {
        try {
            return objectMapper.readValue(metadata.toFile(), ESignatureTemplate.class);
        } catch (RuntimeException e) {
            log.warn("Failed to read e-signature template metadata at {}", metadata, e);
            return null;
        }
    }

    private void ensureOwned(ESignatureTemplate template, ActorContext actor) {
        if (!Objects.equals(template.getOwnerId(), principalId(actor))) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "E-signature template not found");
        }
    }

    private String principalId(ActorContext actor) {
        return actor == null || !StringUtils.hasText(actor.principalId())
                ? "system"
                : actor.principalId();
    }

    private Path templatePath(String templateId) {
        validateId(templateId);
        Path path = templatesPath.resolve(templateId).normalize();
        if (!path.startsWith(templatesPath)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid template id");
        }
        return path;
    }

    private void validateId(String templateId) {
        if (!StringUtils.hasText(templateId) || !SAFE_ID_PATTERN.matcher(templateId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid template id");
        }
    }

    @RequiredArgsConstructor
    private static class StoredMultipartFile implements MultipartFile {
        private final String originalFilename;
        private final Path path;

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return "application/pdf";
        }

        @Override
        public boolean isEmpty() {
            return getSize() == 0;
        }

        @Override
        public long getSize() {
            try {
                return Files.size(path);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read stored template document", e);
            }
        }

        @Override
        public byte[] getBytes() throws IOException {
            return Files.readAllBytes(path);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return Files.newInputStream(path);
        }

        @Override
        public void transferTo(java.io.File destination) throws IOException {
            Files.copy(path, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
