package by.beloil.ai.mdm.hibernate;

import by.beloil.ai.mdm.config.AppConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Utility component for executing Hibernate database read and batch write operations
 * with built-in resilience (Exponential Backoff with Jitter) and first-level cache management.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class HibernateBatchExecutor {

    private final SessionFactory sessionFactory;
    private final AppConfig appConfig;

    /** Default batch size for flushing and clearing the Hibernate persistence context. */
    private static final int DEFAULT_BATCH_SIZE = 50;

    /**
     * Executes a read operation within a managed Hibernate session with retry support.
     *
     * @param operationName descriptive name of the operation for logging purposes
     * @param action        callback function receiving an open {@link Session}
     * @param <T>           the return type of the read operation
     * @return the result produced by the read action
     */
    public <T> T executeRead(String operationName, Function<Session, T> action) {
        return executeWithRetry(operationName, () -> {
            try (Session session = sessionFactory.openSession()) {
                return action.apply(session);
            }
        });
    }

    /**
     * Batch-persists (INSERT/UPDATE via {@code merge}) a list of JPA entities within a single transaction,
     * periodically flushing and clearing the first-level cache to prevent memory bloat,
     * wrapped in an Exponential Backoff retry policy.
     *
     * @param operationName descriptive name of the batch operation for logging purposes
     * @param entities      list of JPA entities to persist; ignored if {@code null} or empty
     * @param <T>           the entity type
     */
    public <T> void saveBatch(String operationName, List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }

        executeWithRetry(operationName, () -> {
            Transaction tx = null;
            try (Session session = sessionFactory.openSession()) {
                tx = session.beginTransaction();
                for (int i = 0; i < entities.size(); i++) {
                    session.merge(entities.get(i));
                    if ((i + 1) % DEFAULT_BATCH_SIZE == 0) {
                        session.flush();
                        session.clear();
                    }
                }
                tx.commit();
                return null;
            } catch (Exception e) {
                if (tx != null && tx.isActive()) {
                    tx.rollback();
                }
                throw e;
            }
        });
    }

    /**
     * Resilience wrapper implementing Exponential Backoff with Jitter,
     * enforcing an overall time budget tracked via monotonic {@link System#nanoTime()}.
     *
     * @param operationName descriptive name of the operation for logging purposes
     * @param action        supplier encapsulating the database operation to execute
     * @param <T>           the return type of the operation
     * @return the result of the successful execution
     */
    private <T> T executeWithRetry(String operationName, Supplier<T> action) {
        long maxBudgetNanos = appConfig.getSpaceType().getDbRetry().getMaxTimeoutMs() * 1_000_000L;
        long currentDelayMs = appConfig.getSpaceType().getDbRetry().getInitialTimeoutMs();
        long startNanos = System.nanoTime();
        int attempt = 1;

        while (true) {
            try {
                return action.get();
            } catch (Exception ex) {
                long elapsedNanos = System.nanoTime() - startNanos;
                if (elapsedNanos >= maxBudgetNanos) {
                    log.error("Operation '{}' exhausted its retry time budget.", operationName, ex);
                    throw ex;
                }
                long jitterMs = ThreadLocalRandom.current().nextLong(0, currentDelayMs / 2 + 1);
                long sleepMs = Math.min(currentDelayMs + jitterMs, (maxBudgetNanos - elapsedNanos) / 1_000_000L);
                log.warn("Database failure during operation '{}' (attempt {}). Retrying in {} ms. Cause: {}",
                        operationName, attempt++, sleepMs, ex.getMessage());
                try {
                    Thread.sleep(Math.max(100, sleepMs));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Operation interrupted while waiting for retry", ie);
                }
                currentDelayMs *= 2;
            }
        }
    }
}
