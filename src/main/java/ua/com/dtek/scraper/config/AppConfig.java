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

public class AppConfig {

    private static final String CONFIG_FILE_NAME = "config.properties";
    private String botToken;
    private String botUsername;
    private String databasePath;
    private int maxConcurrentChecks;
    private final Map<String, Address> addresses = new HashMap<>();

    public void loadConfig() throws IOException {
        System.out.println("Loading configuration from " + CONFIG_FILE_NAME + "...");
        Properties props = new Properties();

        try (InputStream input = getInputStream()) {
            if (input == null) {
                throw new IOException("Unable to find " + CONFIG_FILE_NAME);
            }
            props.load(new InputStreamReader(input, StandardCharsets.UTF_8));

            this.botToken = props.getProperty("bot.token");
            this.botUsername = props.getProperty("bot.username");
            this.databasePath = props.getProperty("database.path", "dtek_bot.db");
            this.maxConcurrentChecks = Integer.parseInt(props.getProperty("thread.pool.max.concurrent.checks", "3"));

            loadAddresses(props);
            System.out.println("Configuration loaded successfully. Found " + addresses.size() + " addresses.");

        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Failed to load configuration: " + e.getMessage());
            throw e;
        }
    }

    private InputStream getInputStream() throws IOException {
        try {
            return new FileInputStream(CONFIG_FILE_NAME);
        } catch (IOException e) {
            return getClass().getClassLoader().getResourceAsStream(CONFIG_FILE_NAME);
        }
    }

    private void loadAddresses(Properties props) {
        for (int i = 1; i <= 4; i++) {
            String name = props.getProperty("address." + i + ".name");
            if (name == null) break;

            String city = props.getProperty("address." + i + ".city");
            String street = props.getProperty("address." + i + ".street");
            String houseNum = props.getProperty("address." + i + ".houseNum");

            if (city == null || street == null || houseNum == null) {
                throw new IllegalArgumentException("Address " + i + " is missing required fields.");
            }

            String addressIdKey = "address." + i;
            // Передаємо 5 аргументів
            addresses.put(addressIdKey, new Address(addressIdKey, name, city, street, houseNum));
        }

        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("No addresses found in config.");
        }
    }

    public String getBotToken() { return botToken; }
    public String getBotUsername() { return botUsername; }
    public String getDatabasePath() { return databasePath; }
    public int getMaxConcurrentChecks() { return maxConcurrentChecks; }
    public Map<String, Address> getAddresses() { return addresses; }
}