package stirling.software.SPDF.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import lombok.Getter;

/**
 * A {@link ResponseStatusException} that carries a stable, machine-readable {@code code} and
 * optional structured properties. {@link GlobalExceptionHandler} renders both on the Problem
 * Details body so clients can branch on the code instead of parsing the message.
 */
@Getter
public class CodedResponseStatusException extends ResponseStatusException {

    private final String code;
    private final Map<String, Object> properties;

    public CodedResponseStatusException(HttpStatus status, String code, String reason) {
        this(status, code, reason, Map.of());
    }

    public CodedResponseStatusException(
            HttpStatus status, String code, String reason, Map<String, Object> properties) {
        super(status, reason);
        this.code = code;
        this.properties = new LinkedHashMap<>(properties == null ? Map.of() : properties);
    }
}
