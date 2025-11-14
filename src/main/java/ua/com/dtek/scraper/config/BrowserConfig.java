package ua.com.dtek.scraper.config;

import com.codeborne.selenide.Configuration;
// --- (FIX v4.4.5) ---
// Імпортуємо 'FirefoxOptions' для надійного налаштування
import org.openqa.selenium.firefox.FirefoxOptions;
// --- END FIX ---

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 *
 * @version 4.4.5 (Final Fix: Using FirefoxOptions object)
 */
public final class BrowserConfig {

    public static final String DTEK_URL = "https://www.dtek-dnem.com.ua/ua/shutdowns";

    /**
     *
     */
    private BrowserConfig() {
        // Utility class
    }

    /**
     *
     */
    public static void setupSelenide() {
        Configuration.browser = "firefox";
        Configuration.browserSize = "1920x1080";
        Configuration.headless = true;
        Configuration.timeout = 10000; // 10 секунд
        Configuration.pageLoadTimeout = 30000; // 30 секунд

        // --- (FIX v4.4.5) ---

        String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:102.0) Gecko/20100101 Firefox/102.0";

        FirefoxOptions options = new FirefoxOptions();
        options.addArguments("-headless", "-width=1920", "-height=1080");
        options.addPreference("general.useragent.override", userAgent);
        options.setCapability("se:bidiEnabled", false);

        Configuration.browserCapabilities = options;
        // --- END FIX ---
    }
}