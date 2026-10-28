package com.kflow.erp.iam.application.credentials;

import static com.kflow.erp.iam.domain.credentials.CredentialFailure.Code.*;

import com.kflow.erp.iam.domain.credentials.CredentialFailure;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Synchronous bounded work: a timed-out/interrupted caller cannot free a still-running hash slot.
 */
@Component
public class PasswordWorkBudget {
  private final Semaphore admitted, running;
  private final long waitMillis;

  public PasswordWorkBudget(
      @Value("${kflow.credentials.hash-workers:2}") int workers,
      @Value("${kflow.credentials.hash-queue:4}") int queue,
      @Value("${kflow.credentials.hash-wait-ms:2000}") long wait) {
    if (workers < 1 || workers > 4 || queue < 0 || queue > 16 || wait < 1 || wait > 10000)
      throw new IllegalArgumentException("Invalid bounded hash budget");
    running = new Semaphore(workers, true);
    admitted = new Semaphore(workers + queue, true);
    waitMillis = wait;
  }

  public <T> T execute(Supplier<T> work) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new CredentialFailure(TRANSACTION_BOUNDARY_VIOLATION);
    if (!admitted.tryAcquire()) throw new CredentialFailure(HASH_CAPACITY);
    boolean acquired = false;
    try {
      acquired = running.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
      if (!acquired) throw new CredentialFailure(HASH_CAPACITY);
      if (Thread.currentThread().isInterrupted()) throw new CredentialFailure(HASH_CAPACITY);
      T result = work.get();
      if (Thread.currentThread().isInterrupted()) throw new CredentialFailure(HASH_CAPACITY);
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CredentialFailure(HASH_CAPACITY);
    } finally {
      if (acquired) running.release();
      admitted.release();
    }
  }
}
