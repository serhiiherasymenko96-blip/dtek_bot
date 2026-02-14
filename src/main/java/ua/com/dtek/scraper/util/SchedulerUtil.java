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
        System.out.println("[DEBUG_LOG] SchedulerUtil.shutdownScheduler: " + logPrefix + " - Starting scheduler shutdown");
        if (scheduler == null) {
            System.out.println("[DEBUG_LOG] SchedulerUtil.shutdownScheduler: " + logPrefix + " - Scheduler is null, nothing to shut down");
            return true;
        }
        
        System.out.println("[DEBUG_LOG] SchedulerUtil.shutdownScheduler: " + logPrefix + " - Initiating graceful shutdown (timeout: " + timeoutSeconds + "s)");
        try {
            long startTime = System.currentTimeMillis();
            scheduler.shutdown();
            System.out.println("[DEBUG_LOG] SchedulerUtil.shutdownScheduler: " + logPrefix + " - Shutdown signal sent, awaiting termination...");
            
            if (!scheduler.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                long elapsedTime = (System.currentTimeMillis() - startTime) / 1000;
                System.err.println("[DEBUG_LOG] SchedulerUtil.shutdownScheduler: " + logPrefix + " - Scheduler did not terminate after " + elapsedTime + "s, forcing immediate shutdown");
                scheduler.shutdownNow();
                System.out.println("[DEBUG_LOG] SchedulerUtil.shutdownScheduler: " + logPrefix + " - Forced shutdown completed");
            } else {
                long elapsedTime = (System.currentTimeMillis() - startTime) / 1000;
                System.out.println("[DEBUG_LOG] SchedulerUtil.shutdownScheduler: " + logPrefix + " - Scheduler terminated gracefully after " + elapsedTime + "s");
            }
            System.out.println("[DEBUG_LOG] SchedulerUtil.shutdownScheduler: " + logPrefix + " - Shutdown successful");
            return true;
        } catch (InterruptedException e) {
            System.err.println("[DEBUG_LOG] SchedulerUtil.shutdownScheduler: " + logPrefix + " - Shutdown interrupted: " + e.getMessage());
            System.out.println("[DEBUG_LOG] SchedulerUtil.shutdownScheduler: " + logPrefix + " - Forcing immediate shutdown due to interruption");
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }
}