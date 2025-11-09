package ua.com.dtek.scraper.service;

import com.google.gson.Gson;
import ua.com.dtek.scraper.dto.Address;
import ua.com.dtek.scraper.dto.TimeInterval;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages all database operations (SQLite).
 * This service handles users, subscriptions, and cached schedules.
 * <p>
 * v4.0.1: Updated to use Gson to serialize List<TimeInterval> into a JSON
 * string for storage in the 'schedules' table.
 */
public class DatabaseService {

    private final String dbUrl;
    private final Gson gson = new Gson(); // For JSON serialization

    /**
     * Constructs the database service.
     *
     * @param databasePath The path to the SQLite database file (from config).
     */
    public DatabaseService(String databasePath) {
        this.dbUrl = "jdbc:sqlite:" + databasePath;
        System.out.println("Database service initialized. DB file at: " + databasePath);
    }

    /**
     * Initializes the database by creating all necessary tables if they don't exist.
     * Also loads the 4 monitored addresses into the 'addresses' table.
     *
     * @param addresses The map of addresses from AppConfig.
     */
    public void initDatabase(Map<String, Address> addresses) {
        // SQL statements to create tables
        String sqlUsers = "CREATE TABLE IF NOT EXISTS users ("
                + " chat_id INTEGER PRIMARY KEY,"
                + " first_name TEXT NOT NULL,"
                + " selected_address_key TEXT,"
                + " FOREIGN KEY (selected_address_key) REFERENCES addresses(address_key)"
                + ");";

        String sqlAddresses = "CREATE TABLE IF NOT EXISTS addresses ("
                + " address_key TEXT PRIMARY KEY,"
                + " name TEXT NOT NULL,"
                + " city TEXT NOT NULL,"
                + " street TEXT NOT NULL,"
                + " house_num TEXT NOT NULL"
                + ");";

        String sqlSchedules = "CREATE TABLE IF NOT EXISTS schedules ("
                + " address_key TEXT PRIMARY KEY,"
                + " schedule_json TEXT,"
                + " last_checked TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + " FOREIGN KEY (address_key) REFERENCES addresses(address_key)"
                + ");";

        String sqlWarnedUsers = "CREATE TABLE IF NOT EXISTS warned_users ("
                + " chat_id INTEGER NOT NULL,"
                + " address_key TEXT NOT NULL,"
                + " shutdown_start_time TEXT NOT NULL,"
                + " PRIMARY KEY (chat_id, address_key, shutdown_start_time),"
                + " FOREIGN KEY (chat_id) REFERENCES users(chat_id),"
                + " FOREIGN KEY (address_key) REFERENCES addresses(address_key)"
                + ");";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {

            // Execute table creation
            stmt.execute(sqlUsers);
            stmt.execute(sqlAddresses);
            stmt.execute(sqlSchedules);
            stmt.execute(sqlWarnedUsers);

            System.out.println("Database tables checked/created successfully.");

            // Populate/Update the 'addresses' table from config.properties
            loadAddressesIntoDb(conn, addresses);

        } catch (SQLException e) {
            System.err.println("FATAL: Failed to initialize database: " + e.getMessage());
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    /**
     * Loads the 4 addresses from the config file into the database.
     * Uses 'REPLACE INTO' to ensure the data is always up-to-date
     * with the config file on every application start.
     */
    private void loadAddressesIntoDb(Connection conn, Map<String, Address> addresses) throws SQLException {
        String sql = "REPLACE INTO addresses (address_key, name, city, street, house_num) VALUES (?, ?, ?, ?, ?);";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false); // Start transaction

            for (Map.Entry<String, Address> entry : addresses.entrySet()) {
                Address addr = entry.getValue();
                pstmt.setString(1, entry.getKey()); // "address_1"
                pstmt.setString(2, addr.name());
                pstmt.setString(3, addr.city());
                pstmt.setString(4, addr.street());
                pstmt.setString(5, addr.houseNum());
                pstmt.addBatch();
            }

            pstmt.executeBatch();
            conn.commit(); // Commit transaction

            System.out.println("Successfully loaded/updated " + addresses.size() + " addresses into database.");
        } catch (SQLException e) {
            conn.rollback(); // Rollback on error
            System.err.println("Failed to load addresses into database: " + e.getMessage());
        } finally {
            conn.setAutoCommit(true); // Restore default behavior
        }
    }

    /**
     * Registers a new user or updates their name on /start.
     */
    public void registerUser(long chatId, String firstName) {
        String sql = "REPLACE INTO users (chat_id, first_name, selected_address_key) VALUES (?, ?, "
                + "(SELECT selected_address_key FROM users WHERE chat_id = ?));";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, chatId);
            pstmt.setString(2, firstName);
            pstmt.setLong(3, chatId); // For the sub-select
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error registering user " + chatId + ": " + e.getMessage());
        }
    }

    /**
     * Saves the user's chosen address subscription.
     */
    public void setUserAddress(long chatId, String addressKey) {
        String sql = "UPDATE users SET selected_address_key = ? WHERE chat_id = ?;";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, addressKey);
            pstmt.setLong(2, chatId);
            pstmt.executeUpdate();
            System.out.println("User " + chatId + " subscribed to " + addressKey);
        } catch (SQLException e) {
            System.err.println("Error setting address for user " + chatId + ": " + e.getMessage());
        }
    }

    /**
     * Gets all users subscribed to a specific address.
     */
    public List<Long> getUsersForAddress(String addressKey) {
        List<Long> userIds = new ArrayList<>();
        String sql = "SELECT chat_id FROM users WHERE selected_address_key = ?;";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, addressKey);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    userIds.add(rs.getLong("chat_id"));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting users for address " + addressKey + ": " + e.getMessage());
        }
        return userIds;
    }

    /**
     * Saves the latest schedule for an address.
     * The schedule list is serialized to a JSON string.
     */
    public void saveSchedule(String addressKey, List<TimeInterval> schedule) {
        String sql = "REPLACE INTO schedules (address_key, schedule_json, last_checked) VALUES (?, ?, CURRENT_TIMESTAMP);";
        String scheduleJson = gson.toJson(schedule); // Serialize to JSON

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, addressKey);
            pstmt.setString(2, scheduleJson);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error saving schedule for " + addressKey + ": " + e.getMessage());
        }
    }

    /**
     * Gets the last saved schedule (as a JSON string) for an address.
     * NotificationService will be responsible for deserializing this JSON.
     */
    public String getSchedule(String addressKey) {
        String sql = "SELECT schedule_json FROM schedules WHERE address_key = ?;";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, addressKey);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("schedule_json");
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting schedule for " + addressKey + ": " + e.getMessage());
        }
        return null; // Return null if no schedule is found
    }

    /**
     * Gets users for an address who have *not* yet been warned about a specific shutdown.
     */
    public List<Long> getUsersToWarn(String addressKey, String startTime) {
        List<Long> userIds = new ArrayList<>();
        // SQL finds users subscribed to the address (in 'users')
        // who are NOT in the 'warned_users' table for this specific address/time.
        String sql = "SELECT u.chat_id FROM users u "
                + "LEFT JOIN warned_users w ON u.chat_id = w.chat_id "
                + "AND w.address_key = ? AND w.shutdown_start_time = ? "
                + "WHERE u.selected_address_key = ? AND w.chat_id IS NULL;";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, addressKey);
            pstmt.setString(2, startTime);
            pstmt.setString(3, addressKey);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    userIds.add(rs.getLong("chat_id"));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error in getUsersToWarn for " + addressKey + ": " + e.getMessage());
        }
        return userIds;
    }

    /**
     * Marks a list of users as "warned" for a specific shutdown time
     * to prevent spamming them.
     */
    public void markUsersAsWarned(List<Long> userIds, String addressKey, String startTime) {
        String sql = "REPLACE INTO warned_users (chat_id, address_key, shutdown_start_time) VALUES (?, ?, ?);";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false); // Start transaction
            for (Long chatId : userIds) {
                pstmt.setLong(1, chatId);
                pstmt.setString(2, addressKey);
                pstmt.setString(3, startTime);
                pstmt.addBatch();
            }
            pstmt.executeBatch();
            conn.commit(); // Commit transaction

        } catch (SQLException e) {
            System.err.println("Error in markUsersAsWarned: " + e.getMessage());
        }
    }

    /**
     * Clears all "warned" flags for a specific address.
     * This is called when the schedule changes.
     */
    public void clearWarnedFlags(String addressKey) {
        String sql = "DELETE FROM warned_users WHERE address_key = ?;";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, addressKey);
            int rowsAffected = pstmt.executeUpdate();
            System.out.println("Cleared " + rowsAffected + " warned flags for address " + addressKey + " due to schedule change.");
        } catch (SQLException e) {
            System.err.println("Error in clearWarnedFlags: " + e.getMessage());
        }
    }
}