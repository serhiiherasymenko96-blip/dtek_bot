package ua.com.dtek.scraper.util;

import java.util.function.Supplier;

/**
 * Utility class for retry operations.
 * Provides methods for retrying operations with configurable retry logic.
 */
public class RetryUtil {

    private RetryUtil() {
        // Utility class, prevent instantiation
    }

    /**
     * Executes an operation with retry logic.
     *
     * @param <T> The return type of the operation
     * @param operation The operation to execute
     * @param maxRetries The maximum number of retry attempts
     * @param operationName A descriptive name for the operation (for logging)
     * @param delayMs The delay between retry attempts in milliseconds
     * @return The result of the operation
     * @throws RuntimeException If all retry attempts fail
     */
    public static <T> T withRetry(Supplier<T> operation, int maxRetries, String operationName, long delayMs) {
        System.out.println("[DEBUG_LOG] RetryUtil.withRetry: Starting operation '" + operationName + "' with max " + maxRetries + " retries, delay " + delayMs + "ms");
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    System.out.println("[DEBUG_LOG] RetryUtil.withRetry: Retry attempt " + attempt + "/" + maxRetries + " for '" + operationName + "'");
                    // Pause before retry
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        System.err.println("[DEBUG_LOG] RetryUtil.withRetry: Retry sleep interrupted for '" + operationName + "'");
                        Thread.currentThread().interrupt();
                    }
                } else {
                    System.out.println("[DEBUG_LOG] RetryUtil.withRetry: Initial attempt for '" + operationName + "'");
                }

                T result = operation.get();
                System.out.println("[DEBUG_LOG] RetryUtil.withRetry: Operation '" + operationName + "' succeeded on attempt " + attempt);
                return result;

            } catch (Exception e) {
                lastException = e;
                String msg = "[DEBUG_LOG] RetryUtil.withRetry: Failed at attempt " + attempt + "/" + maxRetries + " for '" + operationName + "': " + e.getMessage();
                System.err.println(msg);

                if (attempt == maxRetries) {
                    System.err.println("[DEBUG_LOG] RetryUtil.withRetry: All retry attempts exhausted for '" + operationName + "'");
                } else {
                    System.out.println("[DEBUG_LOG] RetryUtil.withRetry: Will retry in " + (delayMs / 1000.0) + " seconds...");
                }
            }
        }

        // If we get here, all retries failed
        String finalMsg = "[DEBUG_LOG] RetryUtil.withRetry: FINAL FAILURE after " + maxRetries + " retries for '" + operationName + "': " + 
                          (lastException != null ? lastException.getMessage() : "Unknown error");
        System.err.println(finalMsg);
        throw new RuntimeException(finalMsg, lastException);
    }
}