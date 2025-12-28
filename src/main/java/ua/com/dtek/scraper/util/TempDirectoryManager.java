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
        try {
            // Create temp directory path
            Path tempDir = Paths.get(TEMP_DIR_NAME);

            // Create directory if it doesn't exist
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
                System.out.println("Created temporary directory: " + tempDir.toAbsolutePath());
            }

            // Clean up existing files
            File dir = tempDir.toFile();
            int deletedFiles = 0;
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile() && file.getName().contains("rust_mozprofile")) {
                            if (file.delete()) {
                                deletedFiles++;
                            }
                        }
                    }
                }
            }

            if (deletedFiles > 0) {
                System.out.println("Cleaned up " + deletedFiles + " temporary browser profile files");
            }

            // Set the java.io.tmpdir property to our controlled directory
            System.setProperty("java.io.tmpdir", tempDir.toAbsolutePath().toString());
            System.out.println("Set java.io.tmpdir to: " + System.getProperty("java.io.tmpdir"));

        } catch (IOException e) {
            System.err.println("Failed to setup temporary directory: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Performs a deep cleaning of the temporary directory by removing files older than 1 day.
     * This helps prevent disk space issues from accumulating temporary files.
     */
    public static void deepCleanTempDirectory() {
        try {
            Path tempDir = Paths.get(TEMP_DIR_NAME);
            if (!Files.exists(tempDir)) {
                return;
            }

            System.out.println("Starting deep cleaning of temporary directory...");

            // Vidalyayemo vsi faily starshi za 1 den'
            Files.walkFileTree(tempDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isRegularFile() && 
                        Files.getLastModifiedTime(file).toInstant().isBefore(Instant.now().minus(Duration.ofDays(1)))) {
                        Files.delete(file);
                        System.out.println("Deleted old temp file: " + file.getFileName());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    System.err.println("Failed to access file: " + file.getFileName() + " - " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });

            System.out.println("Deep cleaning of temporary directory completed");
        } catch (IOException e) {
            System.err.println("Failed to deep clean temporary directory: " + e.getMessage());
        }
    }
}
