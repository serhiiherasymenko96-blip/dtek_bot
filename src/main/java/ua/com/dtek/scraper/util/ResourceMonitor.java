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
        this.scheduler = scheduler;
        this.tempDirPath = tempDirPath;
        this.databasePath = databasePath;
    }

    /**
     * Starts periodic monitoring of system resources.
     */
    public void startMonitoring() {
        // Моніторинг пам'яті
        scheduler.scheduleAtFixedRate(this::monitorMemory, 5, 30, TimeUnit.MINUTES);
        
        // Моніторинг диску
        scheduler.scheduleAtFixedRate(this::monitorDiskSpace, 10, 60, TimeUnit.MINUTES);
    }

    /**
     * Monitors memory usage and logs warnings if it's too high.
     */
    private void monitorMemory() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
            long maxMemory = runtime.maxMemory() / 1024 / 1024;
            
            System.out.println("[MONITOR] Memory usage: " + usedMemory + "MB / " + maxMemory + "MB");
            
            // Критичний поріг - 80% від доступної пам'яті
            if (usedMemory > maxMemory * 0.8) {
                System.err.println("[CRITICAL] Memory usage is too high: " + usedMemory + "MB");
                // Запускаємо збирач сміття при високому використанні пам'яті
                System.gc();
            }
        } catch (Exception e) {
            System.err.println("[MONITOR] Error monitoring memory: " + e.getMessage());
        }
    }

    /**
     * Monitors disk space usage and logs warnings if it's too high.
     * Also triggers cleanup if necessary.
     */
    private void monitorDiskSpace() {
        try {
            // Перевіряємо тимчасову директорію
            File tempDir = new File(tempDirPath);
            long tempDirSize = getFolderSize(tempDir) / 1024 / 1024;
            
            // Перевіряємо директорію з базою даних
            File dbFile = new File(databasePath);
            File dbDir = dbFile.getParentFile();
            long dbDirSize = getFolderSize(dbDir) / 1024 / 1024;
            long dbFileSize = dbFile.length() / 1024 / 1024;
            
            System.out.println("[MONITOR] Temp directory size: " + tempDirSize + "MB");
            System.out.println("[MONITOR] Database directory size: " + dbDirSize + "MB");
            System.out.println("[MONITOR] Database file size: " + dbFileSize + "MB");
            
            // Якщо розмір тимчасової директорії перевищує 1GB
            if (tempDirSize > 1000) {
                System.err.println("[CRITICAL] Temp directory size is too large: " + tempDirSize + "MB");
                TempDirectoryManager.deepCleanTempDirectory();
            }
            
            // Якщо розмір бази даних перевищує 100MB
            if (dbFileSize > 100) {
                System.err.println("[WARNING] Database size is large: " + dbFileSize + "MB");
            }
        } catch (Exception e) {
            System.err.println("[MONITOR] Error monitoring disk space: " + e.getMessage());
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