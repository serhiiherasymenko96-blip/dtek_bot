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
        Configuration.headless = true; // <-- Set to true for background execution
        Configuration.timeout = 10000; // 10 seconds default timeout
        Configuration.pageLoadTimeout = 30000; // 30 seconds page load timeout

        // Set a more human-like user agent
        Configuration.browserCapabilities.setCapability("goog:chromeOptions",
                java.util.Map.of("args", List.of(
                        "--disable-gpu",
                        "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36"
                ))
        );
    }
}