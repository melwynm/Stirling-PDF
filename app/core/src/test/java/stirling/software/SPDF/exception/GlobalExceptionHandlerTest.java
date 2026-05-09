package stirling.software.SPDF.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import jakarta.servlet.http.HttpServletRequest;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        MessageSource messageSource = mock(MessageSource.class);
        Environment environment = mock(Environment.class);
        request = mock(HttpServletRequest.class);

        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(request.getRequestURI()).thenReturn("/api/v1/general/rotate-pdf");

        handler = new GlobalExceptionHandler(messageSource, environment);
    }

    @Test
    void maxUploadSizeExceededReturnsStructuredPayloadTooLargeProblem() {
        ResponseEntity<ProblemDetail> response =
                handler.handleMaxUploadSize(
                        new MaxUploadSizeExceededException(4L * 1024 * 1024), request);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(4L, response.getBody().getProperties().get("maxSizeMB"));
        assertEquals(
                "Reduce the file size to be within the upload limit.",
                response.getBody().getProperties().get("actionRequired"));
    }
}
