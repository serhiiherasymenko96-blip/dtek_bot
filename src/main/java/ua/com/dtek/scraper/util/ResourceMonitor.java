package ua.com.dtek.scraper.util;

import java.io.File;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Utility class for monitoring system resources like memory and disk space.
 * Helps prevent resource exhaustion on limited hardware.
 */
public class ResourceMonitor {

    private final ScheduledExecutorService scheduler;
    private final String tempDirPath;
    private final String databasePath;

    /**
     * Creates a new ResourceMonitor.
     *
     * @param scheduler The scheduler to use for periodic monitoring
     * @param tempDirPath Path to the temporary directory
     * @param databasePath Path to the database file
     */
    public ResourceMonitor(ScheduledExecutorService scheduler, String tempDirPath, String databasePath) {
        System.out.println("[DEBUG_LOG] ResourceMonitor: Initializing resource monitor");
        this.scheduler = scheduler;
        this.tempDirPath = tempDirPath;
        this.databasePath = databasePath;
        System.out.println("[DEBUG_LOG] ResourceMonitor: Monitoring paths - Temp: " + tempDirPath + ", Database: " + databasePath);
    }

    /**
     * Starts periodic monitoring of system resources.
     */
    public void startMonitoring() {
        System.out.println("[DEBUG_LOG] ResourceMonitor.startMonitoring: Starting periodic resource monitoring");
        // Моніторинг пам'яті
        scheduler.scheduleAtFixedRate(this::monitorMemory, 5, 30, TimeUnit.MINUTES);
        System.out.println("[DEBUG_LOG] ResourceMonitor.startMonitoring: Memory monitoring scheduled (initial: 5min, interval: 30min)");
        
        // Моніторинг диску
        scheduler.scheduleAtFixedRate(this::monitorDiskSpace, 10, 60, TimeUnit.MINUTES);
        System.out.println("[DEBUG_LOG] ResourceMonitor.startMonitoring: Disk space monitoring scheduled (initial: 10min, interval: 60min)");
    }

    /**
     * Monitors memory usage and logs warnings if it's too high.
     */
    private void monitorMemory() {
        System.out.println("[DEBUG_LOG] ResourceMonitor.monitorMemory: Running memory check");
        try {
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory() / 1024 / 1024;
            long freeMemory = runtime.freeMemory() / 1024 / 1024;
            long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
            long maxMemory = runtime.maxMemory() / 1024 / 1024;
            double usagePercent = (usedMemory * 100.0) / maxMemory;
            
            System.out.println("[MONITOR] Memory - Used: " + usedMemory + "MB, Total: " + totalMemory + "MB, Free: " + freeMemory + "MB, Max: " + maxMemory + "MB (" + String.format("%.1f", usagePercent) + "% used)");
            
            // Критичний поріг - 80% від доступної пам'яті
            if (usedMemory > maxMemory * 0.8) {
                System.err.println("[CRITICAL] Memory usage exceeds 80% threshold: " + usedMemory + "MB / " + maxMemory + "MB");
                System.out.println("[DEBUG_LOG] ResourceMonitor.monitorMemory: Triggering garbage collection");
                // Запускаємо збирач сміття при високому використанні пам'яті
                System.gc();
                System.out.println("[DEBUG_LOG] ResourceMonitor.monitorMemory: Garbage collection completed");
            } else {
                System.out.println("[DEBUG_LOG] ResourceMonitor.monitorMemory: Memory usage is within acceptable limits");
            }
        } catch (Exception e) {
            System.err.println("[DEBUG_LOG] ResourceMonitor.monitorMemory: Error monitoring memory: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Monitors disk space usage and logs warnings if it's too high.
     * Also triggers cleanup if necessary.
     */
    private void monitorDiskSpace() {
        System.out.println("[DEBUG_LOG] ResourceMonitor.monitorDiskSpace: Running disk space check");
        try {
            // Перевіряємо тимчасову директорію
            File tempDir = new File(tempDirPath);
            System.out.println("[DEBUG_LOG] ResourceMonitor.monitorDiskSpace: Calculating temp directory size: " + tempDirPath);
            long tempDirSize = getFolderSize(tempDir) / 1024 / 1024;
            
            // Перевіряємо директорію з базою даних
            File dbFile = new File(databasePath);
            File dbDir = dbFile.getParentFile();
            System.out.println("[DEBUG_LOG] ResourceMonitor.monitorDiskSpace: Calculating database directory size");
            long dbDirSize = getFolderSize(dbDir) / 1024 / 1024;
            long dbFileSize = dbFile.length() / 1024 / 1024;
            
            System.out.println("[MONITOR] Disk Space - Temp dir: " + tempDirSize + "MB, DB dir: " + dbDirSize + "MB, DB file: " + dbFileSize + "MB");
            
            // Якщо розмір тимчасової директорії перевищує 1GB
            if (tempDirSize > 1000) {
                System.err.println("[CRITICAL] Temp directory size exceeds 1GB threshold: " + tempDirSize + "MB");
                System.out.println("[DEBUG_LOG] ResourceMonitor.monitorDiskSpace: Triggering deep clean of temp directory");
                TempDirectoryManager.deepCleanTempDirectory();
                System.out.println("[DEBUG_LOG] ResourceMonitor.monitorDiskSpace: Deep clean completed");
            } else {
                System.out.println("[DEBUG_LOG] ResourceMonitor.monitorDiskSpace: Temp directory size is within acceptable limits");
            }
            
            // Якщо розмір бази даних перевищує 100MB
            if (dbFileSize > 100) {
                System.err.println("[WARNING] Database size exceeds 100MB threshold: " + dbFileSize + "MB");
            } else {
                System.out.println("[DEBUG_LOG] ResourceMonitor.monitorDiskSpace: Database size is within acceptable limits");
            }
        } catch (Exception e) {
            System.err.println("[DEBUG_LOG] ResourceMonitor.monitorDiskSpace: Error monitoring disk space: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Calculates the size of a folder including all its files and subfolders.
     *
     * @param folder The folder to calculate the size of
     * @return The size of the folder in bytes
     */
    private long getFolderSize(File folder) {
        long size = 0;
        if (folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        size += file.length();
                    } else {
                        size += getFolderSize(file);
                    }
                }
            }
        }
        return size;
    }
}