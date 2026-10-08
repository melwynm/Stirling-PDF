package stirling.software.SPDF.controller.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import stirling.software.common.model.ApplicationProperties;

class ESignatureWorkflowControllerTest {

    private final ApplicationProperties properties = new ApplicationProperties();
    private final ESignatureWorkflowController controller =
            new ESignatureWorkflowController(null, null, null, properties);

    @Test
    void signingLinksUseTheConfiguredFrontendUrl() {
        properties.getSystem().setFrontendUrl("https://sign.example.com/");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Host", "attacker.example");
        request.addHeader("X-Forwarded-Host", "attacker.example");

        assertEquals("https://sign.example.com", controller.baseUrl(request));
    }

    @Test
    void signingLinksFallBackToTheContainerResolvedUrl() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("stirling.example");
        request.setServerPort(443);
        request.addHeader("X-Forwarded-Host", "attacker.example");

        assertEquals("https://stirling.example", controller.baseUrl(request));

        request.setServerPort(8443);
        assertEquals("https://stirling.example:8443", controller.baseUrl(request));
    }

    @Test
    void auditIpIgnoresClientSuppliedForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.1");

        assertEquals("203.0.113.7", controller.clientIp(request));
    }
}
