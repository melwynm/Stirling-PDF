package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.service.SsrfProtectionService;

/**
 * These services keep a package-private constructor for test doubles next to their public one.
 * Registering them as annotated classes mirrors component scanning, so this fails if Spring cannot
 * tell which constructor to use and the application would not start.
 */
class SigningServiceWiringTest {

    @Test
    void springConstructsSigningServicesThatAlsoHaveTestConstructors() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            // Plain instances: registering ApplicationProperties as a bean class would also run its
            // @Bean methods, which load settings.yml from disk.
            context.getBeanFactory()
                    .registerSingleton("ssrfProtectionService", mock(SsrfProtectionService.class));
            context.getBeanFactory()
                    .registerSingleton("applicationProperties", new ApplicationProperties());
            context.register(PadesLtvService.class, ESignatureWebhookService.class);
            context.refresh();

            assertNotNull(context.getBean(PadesLtvService.class));
            assertNotNull(context.getBean(ESignatureWebhookService.class));
        }
    }
}
