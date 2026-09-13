package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;

class SigningOtpServiceTest {
    @TempDir Path directory;
    private static final String SUBJECT = "a".repeat(64);
    private final MutableClock clock = new MutableClock();
    private final AtomicReference<String> code = new AtomicReference<>();
    private SigningOtpService service;

    @BeforeEach
    void setUp() {
        service = new SigningOtpService(JsonMapper.builder().build(), directory, clock);
    }

    @Test
    void persistsHashAndRejectsReplayAfterRestart() throws Exception {
        service.issue(SUBJECT, "document-and-consent", code::set);
        var state =
                JsonMapper.builder()
                        .build()
                        .readValue(
                                directory.resolve(SUBJECT + ".json").toFile(),
                                SigningOtpService.State.class);
        assertTrue(SigningAccessCodeHasher.matches(code.get(), state.getCodeHash()));
        assertNotEquals(code.get(), state.getCodeHash());
        SigningOtpService restarted =
                new SigningOtpService(JsonMapper.builder().build(), directory, clock);
        restarted.consume(SUBJECT, "document-and-consent", code.get());
        assertThrows(
                ResponseStatusException.class,
                () -> service.consume(SUBJECT, "document-and-consent", code.get()));
    }

    @Test
    void rejectsChangedDocumentOrConsentAndInvalidatesChallenge() throws Exception {
        service.issue(SUBJECT, "original", code::set);
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.consume(SUBJECT, "changed", code.get()));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertThrows(
                ResponseStatusException.class,
                () -> service.consume(SUBJECT, "original", code.get()));
    }

    @Test
    void expiresAtExactBoundaryAndLimitsResends() throws Exception {
        var challenge = service.issue(SUBJECT, "binding", code::set);
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                assertThrows(
                                ResponseStatusException.class,
                                () -> service.issue(SUBJECT, "binding", code::set))
                        .getStatusCode());
        clock.now = challenge.expiresAt();
        assertThrows(
                ResponseStatusException.class,
                () -> service.consume(SUBJECT, "binding", code.get()));
        service.issue(SUBJECT, "binding", code::set);
        service.consume(SUBJECT, "binding", code.get());
    }

    @Test
    void resendingDoesNotResetAttemptBudget() throws Exception {
        service.issue(SUBJECT, "binding", code::set);
        for (int i = 0; i < 4; i++) {
            assertThrows(
                    ResponseStatusException.class,
                    () -> service.consume(SUBJECT, "binding", "invalid"));
        }
        clock.now = clock.now.plusSeconds(60);
        service.issue(SUBJECT, "binding", code::set);
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                assertThrows(
                                ResponseStatusException.class,
                                () -> service.consume(SUBJECT, "binding", "invalid"))
                        .getStatusCode());
        clock.now = clock.now.plusSeconds(60);
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                assertThrows(
                                ResponseStatusException.class,
                                () -> service.issue(SUBJECT, "binding", code::set))
                        .getStatusCode());
    }

    @Test
    void permitsExactlyOneConcurrentConsumptionAcrossInstances() throws Exception {
        service.issue(SUBJECT, "binding", code::set);
        var second = new SigningOtpService(JsonMapper.builder().build(), directory, clock);
        var start = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        var tasks = new ArrayList<Future<?>>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (var target : new SigningOtpService[] {service, second}) {
                tasks.add(
                        executor.submit(
                                () -> {
                                    try {
                                        start.await();
                                        target.consume(SUBJECT, "binding", code.get());
                                        accepted.incrementAndGet();
                                    } catch (ResponseStatusException expected) {
                                        assertEquals(
                                                HttpStatus.UNAUTHORIZED, expected.getStatusCode());
                                    } catch (Exception error) {
                                        throw new RuntimeException(error);
                                    }
                                }));
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            for (var task : tasks) task.get();
        }
        assertEquals(1, accepted.get());
    }

    @Test
    void failedDeliveryInvalidatesCodeAndDoesNotExposeProviderDetails() throws Exception {
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.issue(
                                        SUBJECT,
                                        "binding",
                                        value -> {
                                            code.set(value);
                                            throw new IllegalStateException(
                                                    "provider-private-credential");
                                        }));
        assertFalse(error.getMessage().contains("provider-private-credential"));
        assertThrows(
                ResponseStatusException.class,
                () -> service.consume(SUBJECT, "binding", code.get()));
        assertFalse(
                Files.readString(directory.resolve(SUBJECT + ".json"))
                        .contains("provider-private-credential"));
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-13T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
