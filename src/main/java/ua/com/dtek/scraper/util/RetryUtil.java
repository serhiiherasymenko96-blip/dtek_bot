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
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    System.out.println("Retry attempt " + attempt + " for " + operationName);
                    // Pause before retry
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }

                return operation.get();

            } catch (Exception e) {
                lastException = e;
                String msg = "[RETRY ERROR] Failed at attempt " + attempt + " for " + operationName + ": " + e.getMessage();
                System.err.println(msg);

                if (attempt == maxRetries) {
                    System.err.println("All retry attempts failed for " + operationName);
                } else {
                    System.out.println("Will retry in " + (delayMs / 1000) + " seconds...");
                }
            }
        }

        // If we get here, all retries failed
        String finalMsg = "[RETRY ERROR] Failed after " + maxRetries + " retries for " + operationName + ": " + 
                          (lastException != null ? lastException.getMessage() : "Unknown error");
        System.err.println(finalMsg);
        throw new RuntimeException(finalMsg, lastException);
    }
}