package ua.com.dtek.scraper.config;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.WebDriverProvider;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

/**
 * Конфігурація браузера через Custom Provider.
 * Це єдиний надійний спосіб повністю вимкнути BiDi (WebSocket) у Selenide 7+,
 * щоб уникнути NullPointerException при читанні логів Firefox.
 *
 * @version 7.2.1 (Fixed: Removed javax.annotation to fix compilation error)
 */
public final class BrowserConfig {

    public static final String DTEK_URL = "https://www.dtek-dnem.com.ua/ua/shutdowns";

    private BrowserConfig() {
    }

    public static void setupSelenide() {
        // Замість "firefox" вказуємо наш клас-провайдер.
        Configuration.browser = CustomFirefoxProvider.class.getName();

        Configuration.browserSize = "1366x768";
        Configuration.headless = true;

        // Збільшуємо таймаути
        Configuration.timeout = 20000;
        Configuration.pageLoadTimeout = 60000;

        Configuration.screenshots = false;
        Configuration.savePageSource = false;
    }

    /**
     * Кастомний провайдер драйвера.
     * Ми створюємо FirefoxDriver вручну, щоб Selenide не додавав свої listeners автоматично.
     */
    public static class CustomFirefoxProvider implements WebDriverProvider {

        // Видалено анотації @Nonnull, щоб уникнути помилок компіляції без зайвих залежностей
        @Override
        public WebDriver createDriver(Capabilities capabilities) {
            FirefoxOptions options = new FirefoxOptions();

            // --- ОСНОВНІ НАЛАШТУВАННЯ ---
            options.addArguments("-headless");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");

            // --- CRITICAL FIX: ВИМИКАЄМО BIDI ---
            // webSocketUrl: null каже Selenium не ініціювати WebSocket з'єднання взагалі.
            // Це має зупинити потік "BiDi Connection", який падає.
            options.setCapability("webSocketUrl", (Object) null);
            options.setCapability("se:bidiEnabled", false);

            // --- ОПТИМІЗАЦІЯ РЕСУРСІВ (RAM/CPU) ---
            options.addPreference("permissions.default.image", 2); // Блокуємо картинки
            options.addPreference("permissions.default.stylesheet", 2); // Блокуємо CSS (якщо парсеру не треба)
            options.addPreference("media.autoplay.default", 0);
            options.addPreference("dom.ipc.processCount", 1); // Мінімум процесів

            // User Agent
            String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/109.0";
            options.addPreference("general.useragent.override", userAgent);

            // Об'єднуємо з capabilities, які міг передати Selenide
            options.merge(capabilities);

            return new FirefoxDriver(options);
        }
    }
}