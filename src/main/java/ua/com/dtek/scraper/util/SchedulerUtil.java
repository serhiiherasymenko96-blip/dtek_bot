package ua.com.dtek.scraper.util;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Utility class for handling scheduler operations.
 * Provides centralized methods for safely shutting down schedulers.
 */
public class SchedulerUtil {

    private SchedulerUtil() {
        // Utility class, prevent instantiation
    }

    /**
     * Safely shuts down a scheduler with proper error handling.
     * 
     * @param scheduler The scheduler to shut down
     * @param timeoutSeconds The maximum time to wait for termination in seconds
     * @param logPrefix A prefix for log messages to identify the source
     * @return true if shutdown was successful, false otherwise
     */
    public static boolean shutdownScheduler(ScheduledExecutorService scheduler, int timeoutSeconds, String logPrefix) {
        if (scheduler == null) return true;
        
        System.out.println(logPrefix + "Shutting down scheduler...");
        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                System.err.println(logPrefix + "Scheduler did not terminate in time, forcing shutdown...");
                scheduler.shutdownNow();
            }
            System.out.println(logPrefix + "Scheduler shut down successfully.");
            return true;
        } catch (InterruptedException e) {
            System.err.println(logPrefix + "Scheduler shutdown interrupted: " + e.getMessage());
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }
}