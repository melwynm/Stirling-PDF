package stirling.software.SPDF.service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import lombok.Data;

import tools.jackson.databind.ObjectMapper;

/**
 * Persistent one-use challenges. Only a salted hash and an opaque document/consent binding are
 * stored.
 */
public final class SigningOtpService {
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration RESEND = Duration.ofSeconds(60);
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);
    private static final int MAX_ATTEMPTS = 5;
    private static final Object[] LOCKS = new Object[256];

    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }

    private final ObjectMapper mapper;
    private final Path directory;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    SigningOtpService(ObjectMapper mapper, Path directory, Clock clock) {
        this.mapper = mapper;
        this.directory = directory;
        this.clock = clock;
    }

    public record Challenge(Instant expiresAt, Instant resendAt) {}

    @FunctionalInterface
    interface Delivery {
        void send(String code) throws Exception;
    }

    @FunctionalInterface
    private interface LockedAction<T> {
        T run(Path path) throws IOException;
    }

    Challenge issue(String subject, String binding, Delivery delivery) throws IOException {
        return locked(
                subject,
                path -> {
                    State state = read(path);
                    Instant now = clock.instant();
                    resetAttemptsIfElapsed(state, now);
                    checkAttempts(state);
                    if (state.getResendAt() != null && now.isBefore(state.getResendAt())) {
                        throw failure(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Please wait before requesting another code");
                    }
                    String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
                    state.setCodeHash(SigningAccessCodeHasher.hash(code));
                    state.setBinding(binding);
                    state.setExpiresAt(now.plus(TTL));
                    state.setResendAt(now.plus(RESEND));
                    state.setConsumed(false);
                    write(path, state);
                    try {
                        delivery.send(code);
                    } catch (Exception e) {
                        state.setConsumed(true);
                        state.setCodeHash(null);
                        write(path, state);
                        throw failure(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "Unable to deliver the signing code");
                    }
                    return new Challenge(state.getExpiresAt(), state.getResendAt());
                });
    }

    void consume(String subject, String binding, String code) throws IOException {
        locked(
                subject,
                path -> {
                    State state = read(path);
                    Instant now = clock.instant();
                    resetAttemptsIfElapsed(state, now);
                    checkAttempts(state);
                    if (state.isConsumed()
                            || state.getExpiresAt() == null
                            || !now.isBefore(state.getExpiresAt())) {
                        throw failure(HttpStatus.UNAUTHORIZED, "Request a new signing code");
                    }
                    if (!binding.equals(state.getBinding())) {
                        state.setConsumed(true);
                        state.setCodeHash(null);
                        write(path, state);
                        throw failure(
                                HttpStatus.CONFLICT,
                                "The document or consent changed; request a new code");
                    }
                    if (code == null
                            || !code.matches("[0-9]{6}")
                            || !SigningAccessCodeHasher.matches(code, state.getCodeHash())) {
                        state.setAttempts(state.getAttempts() + 1);
                        write(path, state);
                        checkAttempts(state);
                        throw failure(HttpStatus.UNAUTHORIZED, "The signing code is incorrect");
                    }
                    // Persist consumption before returning authorization, including if the
                    // subsequent operation fails.
                    state.setConsumed(true);
                    state.setCodeHash(null);
                    write(path, state);
                    return null;
                });
    }

    private void resetAttemptsIfElapsed(State state, Instant now) {
        if (state.getAttemptWindowEndsAt() == null
                || !now.isBefore(state.getAttemptWindowEndsAt())) {
            state.setAttempts(0);
            state.setAttemptWindowEndsAt(now.plus(ATTEMPT_WINDOW));
        }
    }

    private void checkAttempts(State state) {
        if (state.getAttempts() >= MAX_ATTEMPTS) {
            throw failure(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Signing code verification is temporarily locked");
        }
    }

    private <T> T locked(String subject, LockedAction<T> action) throws IOException {
        if (subject == null || !subject.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid challenge subject");
        }
        Files.createDirectories(directory);
        synchronized (LOCKS[(subject.hashCode() & Integer.MAX_VALUE) % LOCKS.length]) {
            try (FileChannel channel =
                            FileChannel.open(
                                    directory.resolve(subject + ".lock"),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                if (!lock.isValid()) throw new IOException("Cannot lock signing challenge");
                return action.run(directory.resolve(subject + ".json"));
            }
        }
    }

    private State read(Path path) throws IOException {
        return Files.exists(path) ? mapper.readValue(path.toFile(), State.class) : new State();
    }

    private void write(Path path, State state) throws IOException {
        Path temporary = Files.createTempFile(directory, "otp-", ".tmp");
        try {
            Files.write(temporary, mapper.writeValueAsBytes(state));
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static ResponseStatusException failure(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    @Data
    static class State {
        private String codeHash;
        private String binding;
        private Instant expiresAt;
        private Instant resendAt;
        private Instant attemptWindowEndsAt;
        private int attempts;
        private boolean consumed;
    }
}
