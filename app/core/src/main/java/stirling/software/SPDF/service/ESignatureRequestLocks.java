package stirling.software.SPDF.service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import lombok.extern.slf4j.Slf4j;

/**
 * Serializes mutations of one e-signature request while keeping independent requests concurrent.
 *
 * <p>Two layers are combined:
 *
 * <ul>
 *   <li>An in-process {@link ReentrantLock} per request id, shared JVM-wide, which is the fast path
 *       and the only coordination available to threads of the same process.
 *   <li>An advisory {@link FileLock} on {@code requests/{requestId}/.lock}, acquired after the
 *       in-process lock and held for the duration of the action. It is what coordinates several
 *       instances that share the request storage. When the filesystem cannot provide a lock the
 *       service logs once and continues with the in-process lock only; that deployment must route
 *       all mutations of a request to one instance.
 * </ul>
 *
 * <p>The in-process lock is reentrant: a nested action on the same request reuses the lock that is
 * already held and never tries to take the file lock a second time, which the JVM would refuse with
 * {@link OverlappingFileLockException}.
 */
@Slf4j
final class ESignatureRequestLocks {

    static final String LOCK_FILE = ".lock";

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final AtomicBoolean FILE_LOCK_WARNING_LOGGED = new AtomicBoolean();

    private final Path requestsPath;

    ESignatureRequestLocks(Path requestsPath) {
        this.requestsPath = requestsPath;
    }

    @FunctionalInterface
    interface LockedAction<T> {
        T run() throws IOException;
    }

    /** True when the current thread already holds the lock for {@code requestId}. */
    boolean isHeldByCurrentThread(String requestId) {
        Entry entry = ENTRIES.get(requestId);
        return entry != null && entry.lock.isHeldByCurrentThread();
    }

    <T> T withLock(String requestId, LockedAction<T> action) throws IOException {
        Entry entry = acquireEntry(requestId);
        entry.lock.lock();
        try {
            if (entry.lock.getHoldCount() == 1) {
                try (FileChannel channel = openLockChannel(requestId);
                        FileLock fileLock = lockFile(channel)) {
                    return action.run();
                }
            }
            return action.run();
        } finally {
            entry.lock.unlock();
            releaseEntry(requestId, entry);
        }
    }

    private Entry acquireEntry(String requestId) {
        return ENTRIES.compute(
                requestId,
                (id, existing) -> {
                    Entry entry = existing == null ? new Entry() : existing;
                    entry.waiters++;
                    return entry;
                });
    }

    private void releaseEntry(String requestId, Entry entry) {
        ENTRIES.computeIfPresent(
                requestId,
                (id, existing) -> {
                    if (existing != entry) {
                        return existing;
                    }
                    existing.waiters--;
                    return existing.waiters <= 0 ? null : existing;
                });
    }

    private FileChannel openLockChannel(String requestId) throws IOException {
        Path requestPath = requestsPath.resolve(requestId).normalize();
        Files.createDirectories(requestPath);
        return FileChannel.open(
                requestPath.resolve(LOCK_FILE),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
    }

    private FileLock lockFile(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.lock();
            if (lock == null || !lock.isValid()) {
                warnFileLockUnavailable(null);
                return null;
            }
            return lock;
        } catch (IOException | OverlappingFileLockException e) {
            warnFileLockUnavailable(e);
            return null;
        }
    }

    private void warnFileLockUnavailable(Exception cause) {
        if (FILE_LOCK_WARNING_LOGGED.compareAndSet(false, true)) {
            log.warn(
                    "The e-signature request storage does not support file locks; mutations are"
                            + " serialized within this process only. Multi-instance deployments"
                            + " must route each request to a single instance.",
                    cause);
        }
    }

    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock();
        private int waiters;
    }
}
