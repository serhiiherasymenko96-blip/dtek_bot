package ua.com.dtek.scraper.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;

/**
 * Utility class for managing temporary directories.
 * Handles creation, cleanup, and system property setup.
 */
public class TempDirectoryManager {

    private static final String TEMP_DIR_NAME = "temp_browser";

    private TempDirectoryManager() {
        // Utility class, prevent instantiation
    }

    /**
     * Sets up a temporary directory for browser profiles and cleans it.
     * This prevents disk space issues from accumulating browser profiles.
     */
    public static void setupTempDirectory() {
        System.out.println("[DEBUG_LOG] TempDirectoryManager.setupTempDirectory: Starting temp directory setup");
        try {
            // Create temp directory path
            Path tempDir = Paths.get(TEMP_DIR_NAME);
            System.out.println("[DEBUG_LOG] TempDirectoryManager.setupTempDirectory: Target directory: " + tempDir.toAbsolutePath());

            // Create directory if it doesn't exist
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
                System.out.println("[DEBUG_LOG] TempDirectoryManager.setupTempDirectory: Created temporary directory: " + tempDir.toAbsolutePath());
            } else {
                System.out.println("[DEBUG_LOG] TempDirectoryManager.setupTempDirectory: Directory already exists");
            }

            // Clean up existing files
            File dir = tempDir.toFile();
            int deletedFiles = 0;
            int totalFiles = 0;
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    totalFiles = files.length;
                    System.out.println("[DEBUG_LOG] TempDirectoryManager.setupTempDirectory: Found " + totalFiles + " files in temp directory");
                    for (File file : files) {
                        if (file.isFile() && file.getName().contains("rust_mozprofile")) {
                            System.out.println("[DEBUG_LOG] TempDirectoryManager.setupTempDirectory: Deleting rust_mozprofile file: " + file.getName());
                            if (file.delete()) {
                                deletedFiles++;
                            } else {
                                System.err.println("[DEBUG_LOG] TempDirectoryManager.setupTempDirectory: Failed to delete: " + file.getName());
                            }
                        }
                    }
                }
            }

            if (deletedFiles > 0) {
                System.out.println("[DEBUG_LOG] TempDirectoryManager.setupTempDirectory: Cleaned up " + deletedFiles + " / " + totalFiles + " temporary browser profile files");
            } else {
                System.out.println("[DEBUG_LOG] TempDirectoryManager.setupTempDirectory: No cleanup needed");
            }

            // Set the java.io.tmpdir property to our controlled directory
            System.setProperty("java.io.tmpdir", tempDir.toAbsolutePath().toString());
            System.out.println("[DEBUG_LOG] TempDirectoryManager.setupTempDirectory: Set java.io.tmpdir to: " + System.getProperty("java.io.tmpdir"));

        } catch (IOException e) {
            System.err.println("[DEBUG_LOG] TempDirectoryManager.setupTempDirectory: Failed to setup temporary directory: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Performs a deep cleaning of the temporary directory by removing files older than 1 day.
     * This helps prevent disk space issues from accumulating temporary files.
     */
    public static void deepCleanTempDirectory() {
        System.out.println("[DEBUG_LOG] TempDirectoryManager.deepCleanTempDirectory: Starting deep clean");
        try {
            Path tempDir = Paths.get(TEMP_DIR_NAME);
            if (!Files.exists(tempDir)) {
                System.out.println("[DEBUG_LOG] TempDirectoryManager.deepCleanTempDirectory: Temp directory does not exist, nothing to clean");
                return;
            }

            System.out.println("[DEBUG_LOG] TempDirectoryManager.deepCleanTempDirectory: Scanning for files older than 1 day in: " + tempDir.toAbsolutePath());

            final int[] filesChecked = {0};
            final int[] filesDeleted = {0};
            final int[] filesFailed = {0};

            // Vidalyayemo vsi faily starshi za 1 den'
            Files.walkFileTree(tempDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    filesChecked[0]++;
                    if (attrs.isRegularFile() && 
                        Files.getLastModifiedTime(file).toInstant().isBefore(Instant.now().minus(Duration.ofDays(1)))) {
                        try {
                            Files.delete(file);
                            filesDeleted[0]++;
                            System.out.println("[DEBUG_LOG] TempDirectoryManager.deepCleanTempDirectory: Deleted old file: " + file.getFileName());
                        } catch (IOException e) {
                            filesFailed[0]++;
                            System.err.println("[DEBUG_LOG] TempDirectoryManager.deepCleanTempDirectory: Failed to delete: " + file.getFileName() + " - " + e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    filesFailed[0]++;
                    System.err.println("[DEBUG_LOG] TempDirectoryManager.deepCleanTempDirectory: Failed to access file: " + file.getFileName() + " - " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });

            System.out.println("[DEBUG_LOG] TempDirectoryManager.deepCleanTempDirectory: Deep cleaning completed - Checked: " + filesChecked[0] + ", Deleted: " + filesDeleted[0] + ", Failed: " + filesFailed[0]);
        } catch (IOException e) {
            System.err.println("[DEBUG_LOG] TempDirectoryManager.deepCleanTempDirectory: Failed to deep clean temporary directory: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
