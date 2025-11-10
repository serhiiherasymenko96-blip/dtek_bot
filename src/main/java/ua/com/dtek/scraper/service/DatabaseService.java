package ua.com.dtek.scraper.service;

import com.google.gson.Gson;
import ua.com.dtek.scraper.dto.Address;
import ua.com.dtek.scraper.dto.TimeInterval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages all database operations (SQLite).
 * This service handles user subscriptions and caches schedules.
 *
 * @version 4.2.3 (Fixes compilation errors)
 */
public class DatabaseService {

    private final String dbUrl;
    private final Map<String, Address> monitoredAddresses;
    private final Gson gson = new Gson(); // For serializing schedules

    public DatabaseService(String dbPath, Map<String, Address> addresses) {
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        this.monitoredAddresses = addresses;
        System.out.println("Database service initialized. DB file at: " + dbPath);
    }

    /**
     * Initializes the database.
     * Creates tables if they don't exist and populates the addresses table.
     */
    public void initDatabase() {
        // Automatically create parent directories if they don't exist
        try {
            Path dbPath = Paths.get(this.dbUrl.replace("jdbc:sqlite:", ""));
            Files.createDirectories(dbPath.getParent());
        } catch (Exception e) {
            System.err.println("FATAL: Could not create directory for database: " + e.getMessage());
            throw new RuntimeException("Database initialization failed", e);
        }

        // SQL statements for table creation
        String createUsersTable = "CREATE TABLE IF NOT EXISTS users ("
                + " chat_id INTEGER PRIMARY KEY,"
                + " first_name TEXT,"
                + " subscribed_address_key TEXT,"
                + " FOREIGN KEY (subscribed_address_key) REFERENCES addresses (address_key)"
                + ");";

        String createAddressesTable = "CREATE TABLE IF NOT EXISTS addresses ("
                + " address_key TEXT PRIMARY KEY,"
                + " display_name TEXT NOT NULL"
                + ");";

        String createCachedSchedulesTable = "CREATE TABLE IF NOT EXISTS cached_schedules ("
                + " address_key TEXT PRIMARY KEY,"
                + " schedule_json TEXT," // Can be NULL if not yet scraped
                + " last_checked INTEGER NOT NULL DEFAULT 0," // Store as Unix timestamp
                + " FOREIGN KEY (address_key) REFERENCES addresses (address_key)"
                + ");";

        // Table to track which user has been warned about which outage
        String createWarnedUsersTable = "CREATE TABLE IF NOT EXISTS warned_users ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " chat_id INTEGER NOT NULL,"
                + " address_key TEXT NOT NULL,"
                + " outage_start_time TEXT NOT NULL," // e.g., "14:30"
                + " UNIQUE(chat_id, address_key, outage_start_time)"
                + ");";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {

            // Create tables
            stmt.execute(createUsersTable);
            stmt.execute(createAddressesTable);
            stmt.execute(createCachedSchedulesTable);
            stmt.execute(createWarnedUsersTable);

            // Populate/Update addresses table from config.properties
            String insertAddressSql = "INSERT OR REPLACE INTO addresses (address_key, display_name) VALUES (?, ?)";
            String insertScheduleSql = "INSERT OR IGNORE INTO cached_schedules (address_key, schedule_json, last_checked) VALUES (?, ?, 0)";

            try (PreparedStatement psAddr = conn.prepareStatement(insertAddressSql);
                 PreparedStatement psSched = conn.prepareStatement(insertScheduleSql)) {

                for (Map.Entry<String, Address> entry : monitoredAddresses.entrySet()) {
                    // Populate addresses
                    psAddr.setString(1, entry.getKey());
                    psAddr.setString(2, entry.getValue().name());
                    psAddr.addBatch();

                    // --- FIX (v4.2.2) ---
                    // Populate cached_schedules with NULL (unknown), not "[]" (no outages)
                    // This fixes the bug where new users see "No outages" instead of "Loading..."
                    psSched.setString(1, entry.getKey());
                    psSched.setNull(2, Types.VARCHAR);
                    psSched.addBatch();
                }
                psAddr.executeBatch();
                psSched.executeBatch();
            }

            System.out.println("Database tables checked/created successfully.");
            System.out.println("Successfully loaded/updated " + monitoredAddresses.size() + " addresses into database.");

        } catch (SQLException e) {
            System.err.println("FATAL: Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    /**
     * Saves or updates a user's address subscription.
     *
     * @param chatId      The user's Telegram Chat ID.
     * @param addressKey  The address key they subscribed to (e.g., "address.1").
     */
    public void setUserAddress(long chatId, String addressKey) {
        // This method only sets the subscription, not the name.
        String sql = "INSERT OR REPLACE INTO users (chat_id, subscribed_address_key) "
                + "VALUES (?, ?) "
                + "ON CONFLICT(chat_id) DO UPDATE SET subscribed_address_key = excluded.subscribed_address_key";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, chatId);
            pstmt.setString(2, addressKey);
            pstmt.executeUpdate();
            System.out.println("User " + chatId + " subscribed to " + addressKey);
        } catch (SQLException e) {
            System.err.println("Error setting user address: " + e.getMessage());
        }
    }

    /**
     * Updates a user's first name.
     * This is called separately to register or update their name.
     *
     * @param chatId    The user's Telegram Chat ID.
     * @param firstName The user's first name.
     */
    public void updateUserName(long chatId, String firstName) {
        String sql = "INSERT OR REPLACE INTO users (chat_id, first_name) "
                + "VALUES (?, ?) "
                + "ON CONFLICT(chat_id) DO UPDATE SET first_name = excluded.first_name";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, chatId);
            pstmt.setString(2, firstName);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error updating user name: " + e.getMessage());
        }
    }


    /**
     * Retrieves all users subscribed to a specific address.
     *
     * @param addressKey The address key to check.
     * @return A list of Chat IDs.
     */
    public List<Long> getUsersForAddress(String addressKey) {
        List<Long> userIds = new ArrayList<>();
        String sql = "SELECT chat_id FROM users WHERE subscribed_address_key = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, addressKey);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                userIds.add(rs.getLong("chat_id"));
            }
        } catch (SQLException e) {
            System.err.println("Error getting users for address: " + e.getMessage());
        }
        return userIds;
    }

    /**
     * Saves a new schedule to the database cache as a JSON string.
     *
     * @param addressKey The address key.
     * @param schedule   The list of TimeIntervals to save.
     */
    public void saveSchedule(String addressKey, List<TimeInterval> schedule) {
        String scheduleJson = gson.toJson(schedule);
        String sql = "UPDATE cached_schedules SET schedule_json = ?, last_checked = ? WHERE address_key = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, scheduleJson);
            pstmt.setLong(2, System.currentTimeMillis() / 1000L); // Current Unix time
            pstmt.setString(3, addressKey);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error saving schedule to cache: " + e.getMessage());
        }
    }

    /**
     * Retrieves the last cached schedule (as a JSON string) for an address.
     *
     * @param addressKey The address key.
     * @return The schedule as a JSON string, or null if not found.
     */
    public String getSchedule(String addressKey) {
        String sql = "SELECT schedule_json FROM cached_schedules WHERE address_key = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, addressKey);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("schedule_json");
            }
        } catch (SQLException e) {
            System.err.println("Error getting schedule from cache: " + e.getMessage());
        }
        return null;
    }

    /**
     * Finds users who are subscribed to an address AND have not yet been warned
     * about a specific upcoming outage.
     *
     * @param addressKey     The address key.
     * @param outageStartTime The start time of the outage (e.g., "14:30").
     * @return A list of Chat IDs to warn.
     */
    public List<Long> getUsersToWarn(String addressKey, String outageStartTime) {
        List<Long> userIds = new ArrayList<>();
        // SQL JOIN to find users who (are subscribed) AND (are NOT in the warned_users table for this outage)
        String sql = "SELECT u.chat_id FROM users u "
                + " LEFT JOIN warned_users w ON u.chat_id = w.chat_id "
                + " AND w.address_key = ? AND w.outage_start_time = ? "
                + " WHERE u.subscribed_address_key = ? AND w.id IS NULL";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, addressKey);
            pstmt.setString(2, outageStartTime);
            pstmt.setString(3, addressKey);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                userIds.add(rs.getLong("chat_id"));
            }
        } catch (SQLException e) {
            System.err.println("Error getting users to warn: " + e.getMessage());
        }
        return userIds;
    }

    /**
     * Marks a list of users as "warned" for a specific outage to prevent spam.
     *
     * @param userIds         The list of users to mark.
     * @param addressKey      The address key.
     * @param outageStartTime The start time of the outage (e.g., "14:30").
     */
    public void markUsersAsWarned(List<Long> userIds, String addressKey, String outageStartTime) {
        String sql = "INSERT OR IGNORE INTO warned_users (chat_id, address_key, outage_start_time) VALUES (?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            for (Long chatId : userIds) {
                pstmt.setLong(1, chatId);
                pstmt.setString(2, addressKey);
                pstmt.setString(3, outageStartTime);
                pstmt.addBatch();
            }
            pstmt.executeBatch();
            System.out.println("Marked " + userIds.size() + " users as warned for " + addressKey + " at " + outageStartTime);
        } catch (SQLException e) {
            System.err.println("Error marking users as warned: " + e.getMessage());
        }
    }

    /**
     * Clears all warning flags for a given address.
     * This is called when the schedule changes, so users can be re-warned about new times.
     *
     * @param addressKey The address key to clear.
     */
    public void clearWarnedFlags(String addressKey) {
        String sql = "DELETE FROM warned_users WHERE address_key = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, addressKey);
            int rowsAffected = pstmt.executeUpdate();
            System.out.println("Cleared " + rowsAffected + " warned flags for address " + addressKey + " due to schedule change.");
        } catch (SQLException e) {
            System.err.println("Error clearing warned flags: " + e.getMessage());
        }
    }
}