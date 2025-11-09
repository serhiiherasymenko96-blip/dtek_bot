package ua.com.dtek.scraper.config;

import ua.com.dtek.scraper.dto.Address;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Handles loading and providing application-specific configuration
 * from the 'config.properties' file.
 * <p>
 * This version (v4.0.0) is updated to load Bot credentials,
 * database paths, and a dynamic list of addresses.
 */
public class AppConfig {

    private static final String CONFIG_FILE = "config.properties";
    private static final int MAX_ADDRESSES = 4; // Max addresses to check in config

    private String botToken;
    private String botUsername;
    private String databasePath;
    private final Map<String, Address> addresses = new HashMap<>();

    /**
     * Loads all configurations from the {@code config.properties} file.
     * It loads from the file system (same directory as the .jar)
     * to allow server-side configuration.
     *
     * @throws IOException              If the properties file cannot be found or read.
     * @throws IllegalArgumentException If any required property is missing.
     */
    public void loadConfig() throws IOException {
        System.out.println("Loading configuration from " + CONFIG_FILE + "...");
        Properties props = new Properties();

        // Use try-with-resources for automatic stream closing
        // --- SERVER CHANGE ---
        // Load from file system (new FileInputStream) instead of classpath
        // This allows 'config.properties' to be edited next to the .jar on the server.
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(CONFIG_FILE), StandardCharsets.UTF_8)) {

            props.load(reader);

            // 1. Load Bot Configuration
            this.botToken = props.getProperty("bot.token");
            this.botUsername = props.getProperty("bot.username");

            // 2. Load Database Configuration
            this.databasePath = props.getProperty("database.path");

            // 3. Load Monitored Addresses
            // We loop up to MAX_ADDRESSES to find all defined addresses
            for (int i = 1; i <= MAX_ADDRESSES; i++) {
                String addressKey = "address." + i;
                String name = props.getProperty(addressKey + ".name");

                // If a name exists, load the full address
                if (name != null && !name.isEmpty()) {
                    String city = props.getProperty(addressKey + ".city");
                    String street = props.getProperty(addressKey + ".street");
                    String houseNum = props.getProperty(addressKey + ".houseNum");

                    if (city == null || street == null || houseNum == null) {
                        throw new IllegalArgumentException("Address '" + addressKey + "' is incomplete. 'city', 'street', and 'houseNum' are required.");
                    }

                    // Use the key (e.g., "address.1") as the unique ID for buttons
                    String buttonCallbackId = "address_" + i;
                    addresses.put(buttonCallbackId, new Address(name, city, street, houseNum));
                    System.out.println("Loaded address: " + name);
                }
            }

            // 4. Validate properties
            if (this.botToken == null || this.botUsername == null || this.databasePath == null ||
                    this.botToken.isEmpty() || this.botUsername.isEmpty() || this.databasePath.isEmpty()) {
                throw new IllegalArgumentException("Bot token, username, or database path is missing in " + CONFIG_FILE);
            }

            if (this.botToken.equals("YOUR_TELEGRAM_BOT_TOKEN_HERE")) {
                throw new IllegalArgumentException("Please update 'bot.token' in " + CONFIG_FILE + " with your real bot token from @BotFather.");
            }

            if (this.addresses.isEmpty()) {
                throw new IllegalArgumentException("No addresses found in " + CONFIG_FILE + ". Please define at least one 'address.1.name'.");
            }

            System.out.println("Configuration loaded successfully. Found " + addresses.size() + " addresses.");

        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Failed to load or validate configuration file: " + e.getMessage());
            throw e; // Re-throw to stop execution
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

    /**
     * Gets the map of monitored addresses.
     *
     * @return A Map where Key is the callback ID (e.g., "address_1") and Value is the Address object.
     */
    public Map<String, Address> getAddresses() {
        return addresses;
    }
}