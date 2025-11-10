package ua.com.dtek.scraper.config;

import com.codeborne.selenide.Configuration;
import java.util.List;

/**
 * Handles the global configuration for the Selenide browser instance.
 * This is a utility class and is not meant to be instantiated.
 */
public final class BrowserConfig {

    public static final String DTEK_URL = "https://www.dtek-dnem.com.ua/ua/shutdowns";

    /**
     * Private constructor to prevent instantiation.
     */
    private BrowserConfig() {
        // Utility class
    }

    /**
     * Initializes and applies all global Selenide configuration settings.
     */
    public static void setupSelenide() {
        Configuration.browser = "chrome";
        Configuration.browserSize = "1920x1080";
        // CRITICAL for server deployment:
        Configuration.headless = true;
        Configuration.timeout = 20000; // Increased timeout for server
        Configuration.pageLoadTimeout = 40000; // Increased timeout for server

        // Set capabilities for robust server (Linux) execution
        Configuration.browserCapabilities.setCapability("goog:chromeOptions",
                java.util.Map.of("args", List.of(
                        // --- SERVER FIX (v4.0.2) ---
                        // Disables the sandbox, critical for running in containers or minimal VMs
                        "--no-sandbox",
                        // Disables the /dev/shm usage, another common issue in containers
                        "--disable-dev-shm-usage",
                        // --- END FIX ---

                        "--disable-gpu",
                        "--disable-extensions", // Disable extensions
                        "--window-size=1920,1080",
                        "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36"
                ))
        );
    }
}