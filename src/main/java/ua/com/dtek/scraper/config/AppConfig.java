package ua.com.dtek.scraper.config;

import ua.com.dtek.scraper.dto.Address;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Handles loading and providing application-specific configuration
 * from the 'config.properties' file.
 * <p>
 * It implements a fallback mechanism:
 * 1. Tries to load 'config.properties' from the filesystem (e.g., alongside the .jar file on a server).
 * 2. If not found, it falls back to loading from the classpath (e.g., src/main/resources in an IDE).
 */
public class AppConfig {

    private static final String CONFIG_FILE_NAME = "config.properties";

    private String botToken;
    private String botUsername;
    private String databasePath;
    private final Map<String, Address> addresses = new HashMap<>();

    /**
     * Loads configuration from 'config.properties'.
     *
     * @throws IOException              If the properties file cannot be found or read.
     * @throws IllegalArgumentException If any required property is missing.
     */
    public void loadConfig() throws IOException {
        System.out.println("Loading configuration from " + CONFIG_FILE_NAME + "...");
        Properties props = new Properties();

        // Use try-with-resources for automatic stream closing
        try (InputStream input = getInputStream()) {
            if (input == null) {
                // This should not happen due to the logic in getInputStream(), but as a safeguard.
                throw new IOException("Unable to find " + CONFIG_FILE_NAME + " in filesystem or classpath.");
            }

            // Force reading the properties file as UTF-8
            props.load(new InputStreamReader(input, StandardCharsets.UTF_8));

            // Load properties
            this.botToken = props.getProperty("bot.token");
            this.botUsername = props.getProperty("bot.username");
            this.databasePath = props.getProperty("database.path", "dtek_bot.db"); // Default to local file

            // Validate bot properties
            if (this.botToken == null || this.botUsername == null || this.botToken.isEmpty() || this.botUsername.isEmpty() || this.botToken.equals("YOUR_TELEGRAM_BOT_TOKEN_HERE")) {
                throw new IllegalArgumentException("bot.token or bot.username is missing or not set in " + CONFIG_FILE_NAME);
            }

            // Load addresses (e.g., "address.1.name", "address.1.city", ...)
            loadAddresses(props);

            System.out.println("Configuration loaded successfully. Found " + addresses.size() + " addresses.");

        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Failed to load or validate configuration file: " + e.getMessage());
            throw e; // Re-throw to stop execution
        }
    }

    /**
     * Tries to find the config file first in the filesystem, then falls back to classpath.
     *
     * @return An InputStream for the config file.
     * @throws IOException if the file is not found in EITHER location.
     */
    private InputStream getInputStream() throws IOException {
        InputStream input;
        try {
            // 1. Try loading from filesystem (for server deployment)
            // This looks in the current working directory (e.g., /opt/dtek-scraper/)
            input = new FileInputStream(CONFIG_FILE_NAME);
            System.out.println("Loading config from filesystem.");
        } catch (IOException e) {
            // 2. Fallback to loading from Classpath (for IDE / local development)
            // This looks in src/main/resources/
            System.out.println("Config not found in filesystem, falling back to classpath...");
            input = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE_NAME);
            if (input == null) {
                throw new IOException("Unable to find " + CONFIG_FILE_NAME + " in filesystem OR classpath.");
            }
        }
        return input;
    }

    /**
     * Loads all address definitions from the properties file.
     *
     * @param props The loaded Properties object.
     * @throws IllegalArgumentException if an address is incomplete.
     */
    private void loadAddresses(Properties props) {
        // Iterate from 1 to 4 (as defined in config template)
        for (int i = 1; i <= 4; i++) {
            String nameKey = "address." + i + ".name";
            String cityKey = "address." + i + ".city";
            String streetKey = "address." + i + ".street";
            String houseNumKey = "address." + i + ".houseNum";

            String name = props.getProperty(nameKey);
            // If name doesn't exist, assume no more addresses are defined and stop.
            if (name == null || name.trim().isEmpty()) {
                break;
            }

            String city = props.getProperty(cityKey);
            String street = props.getProperty(streetKey);
            String houseNum = props.getProperty(houseNumKey);

            if (city == null || street == null || houseNum == null ||
                    city.trim().isEmpty() || street.trim().isEmpty() || houseNum.trim().isEmpty()) {
                throw new IllegalArgumentException("Address " + i + " ('" + name + "') is missing required fields (city, street, or houseNum).");
            }

            // The key (e.g., "address.1") is used as the ID for database and callback queries
            String addressIdKey = "address." + i;
            addresses.put(addressIdKey, new Address(name, city, street, houseNum));
        }

        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("No addresses found in " + CONFIG_FILE_NAME + ". At least one 'address.1.name' must be defined.");
        }
    }


    // --- Getters ---

    public String getBotToken() {
        return botToken;
    }

    public String getBotUsername() {
        return botUsername;
    }

    public String getDatabasePath() {
        return databasePath;
    }

    public Map<String, Address> getAddresses() {
        return addresses;
    }
}