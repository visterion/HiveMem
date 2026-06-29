package com.hivemem.embedding;

/** Thrown when the embedding service is unreachable/slow after all retries are exhausted.
 *  Distinct from deterministic failures (bad input, dimension mismatch) which are not retried. */
public class EmbeddingUnavailableException extends RuntimeException {
    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
