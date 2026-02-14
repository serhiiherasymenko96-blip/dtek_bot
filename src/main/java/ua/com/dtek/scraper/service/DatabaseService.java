package ua.com.dtek.scraper.service;

import com.google.gson.Gson;
import ua.com.dtek.scraper.dto.Address;
import ua.com.dtek.scraper.dto.AddressInfo;
import ua.com.dtek.scraper.dto.TimeInterval;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DatabaseService {

    private final String dbUrl;
    private final String databasePath;
    private final Map<String, Address> monitoredAddresses;
    private final Gson gson = new Gson();
    private Connection connection;

    public DatabaseService(String dbPath, Map<String, Address> addresses) {
        this.databasePath = dbPath;
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        this.monitoredAddresses = addresses;
        System.out.println("Database service initialized. DB file at: " + dbPath);
    }

    public void initDatabase() {
        try {
            Path dbPath = Paths.get(this.dbUrl.replace("jdbc:sqlite:", ""));
            if (dbPath.getParent() != null) Files.createDirectories(dbPath.getParent());
        } catch (Exception e) {
            throw new RuntimeException("Database initialization failed", e);
        }

        // Таблиці
        String sqlUsers = "CREATE TABLE IF NOT EXISTS users (chat_id INTEGER PRIMARY KEY, first_name TEXT, subscribed_address_key TEXT, FOREIGN KEY (subscribed_address_key) REFERENCES addresses(address_key));";
        String sqlAddr = "CREATE TABLE IF NOT EXISTS addresses (address_key TEXT PRIMARY KEY, display_name TEXT NOT NULL, group_name TEXT, group_last_checked INTEGER NOT NULL DEFAULT 0);";
        String sqlGroups = "CREATE TABLE IF NOT EXISTS groups (group_name TEXT PRIMARY KEY, schedule_json TEXT, last_checked INTEGER NOT NULL DEFAULT 0);";
        String sqlWarn = "CREATE TABLE IF NOT EXISTS warned_users (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, address_key TEXT NOT NULL, outage_start_time TEXT NOT NULL, UNIQUE(chat_id, address_key, outage_start_time));";
        String sqlNextDayGroups = "CREATE TABLE IF NOT EXISTS next_day_groups (group_name TEXT PRIMARY KEY, schedule_json TEXT, last_checked INTEGER NOT NULL DEFAULT 0);";

        try (Connection conn = DriverManager.getConnection(dbUrl); Statement stmt = conn.createStatement()) {
            stmt.execute(sqlUsers);
            stmt.execute(sqlAddr);
            stmt.execute(sqlGroups);
            stmt.execute(sqlWarn);
            stmt.execute(sqlNextDayGroups);

            // Міграція
            try { stmt.execute("ALTER TABLE addresses ADD COLUMN group_name TEXT"); } catch (SQLException e) {}
            try { stmt.execute("ALTER TABLE addresses ADD COLUMN group_last_checked INTEGER NOT NULL DEFAULT 0"); } catch (SQLException e) {}

            // Міграція: видалення префікса "Черга " з існуючих назв груп
            try {
                // Clean group names in addresses table
                stmt.execute("UPDATE addresses SET group_name = SUBSTR(group_name, 7) WHERE group_name LIKE 'Черга %'");
                System.out.println("[MIGRATION] Cleaned group names in addresses table");
                
                // Clean group names in groups table
                stmt.execute("UPDATE groups SET group_name = SUBSTR(group_name, 7) WHERE group_name LIKE 'Черга %'");
                System.out.println("[MIGRATION] Cleaned group names in groups table");
                
                // Clean group names in next_day_groups table
                stmt.execute("UPDATE next_day_groups SET group_name = SUBSTR(group_name, 7) WHERE group_name LIKE 'Черга %'");
                System.out.println("[MIGRATION] Cleaned group names in next_day_groups table");
            } catch (SQLException e) {
                System.err.println("[MIGRATION] Error cleaning group names: " + e.getMessage());
            }

            // Заповнення адрес
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO addresses (address_key, display_name) VALUES (?, ?)")) {
                for (Map.Entry<String, Address> entry : monitoredAddresses.entrySet()) {
                    ps.setString(1, entry.getKey());
                    ps.setString(2, entry.getValue().name());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            System.out.println("Database initialized.");
        } catch (SQLException e) {
            throw new RuntimeException("DB init failed", e);
        }
    }

    public List<AddressInfo> getAddressesForFullCheck(long cacheMin, long groupDays) {
        List<AddressInfo> list = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;
        long cacheCutoff = now - (cacheMin * 60);
        long groupCutoff = now - (groupDays * 86400);

        String sql = "SELECT a.address_key, a.group_name, a.group_last_checked FROM addresses a LEFT JOIN groups g ON a.group_name = g.group_name WHERE a.group_name IS NULL OR a.group_last_checked < ? OR g.last_checked IS NULL OR g.last_checked < ?";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, groupCutoff);
            ps.setLong(2, cacheCutoff);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("address_key");
                    if (monitoredAddresses.containsKey(key)) {
                        list.add(new AddressInfo(key, monitoredAddresses.get(key), rs.getString("group_name"), rs.getLong("group_last_checked")));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting addresses for full check: " + e.getMessage());
            e.printStackTrace();
        }
        return list;
    }

    public String getGroupForAddress(String key) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT group_name FROM addresses WHERE address_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("group_name");
            }
        } catch (SQLException e) {
            System.err.println("Error getting group for address: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    public void updateAddressGroupAndTimestamp(String key, String group) {
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement("UPDATE addresses SET group_name = ?, group_last_checked = ? WHERE address_key = ?")) {
            ps.setString(1, group);
            ps.setLong(2, System.currentTimeMillis() / 1000L);
            ps.setString(3, key);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void saveScheduleForGroup(String group, List<TimeInterval> schedule) {
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO groups (group_name, schedule_json, last_checked) VALUES (?, ?, ?)")) {
            ps.setString(1, group);
            ps.setString(2, gson.toJson(schedule));
            ps.setLong(3, System.currentTimeMillis() / 1000L);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    /**
     * Saves a schedule for a group for the next day.
     * 
     * @param group The group name
     * @param schedule The schedule to save
     */
    public void saveNextDayScheduleForGroup(String group, List<TimeInterval> schedule) {
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO next_day_groups (group_name, schedule_json, last_checked) VALUES (?, ?, ?)")) {
            ps.setString(1, group);
            ps.setString(2, gson.toJson(schedule));
            ps.setLong(3, System.currentTimeMillis() / 1000L);
            ps.executeUpdate();
            System.out.println("Saved next day schedule for group: " + group);
        } catch (SQLException e) { 
            System.err.println("Error saving next day schedule for group: " + e.getMessage());
            e.printStackTrace(); 
        }
    }

    public GroupSchedule getScheduleForGroup(String group) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT schedule_json, last_checked FROM groups WHERE group_name = ?")) {
            ps.setString(1, group);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new GroupSchedule(rs.getString("schedule_json"), rs.getLong("last_checked"));
            }
        } catch (SQLException e) {
            System.err.println("Error getting schedule for group: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Gets a schedule for a group for the next day.
     * 
     * @param group The group name
     * @return The next day's schedule for the group, or null if not found
     */
    public GroupSchedule getNextDayScheduleForGroup(String group) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT schedule_json, last_checked FROM next_day_groups WHERE group_name = ?")) {
            ps.setString(1, group);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new GroupSchedule(rs.getString("schedule_json"), rs.getLong("last_checked"));
            }
        } catch (SQLException e) {
            System.err.println("Error getting next day schedule for group: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Copies all next day schedules to the main schedules.
     * This should be called after midnight to make the next day's schedules the main ones.
     */
    public void copyNextDaySchedulesToMainSchedules() {
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            // First, get all next day schedules
            List<String> groupsToUpdate = new ArrayList<>();
            Map<String, String> scheduleJsonMap = new HashMap<>();

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT group_name, schedule_json FROM next_day_groups")) {
                while (rs.next()) {
                    String groupName = rs.getString("group_name");
                    String scheduleJson = rs.getString("schedule_json");
                    groupsToUpdate.add(groupName);
                    scheduleJsonMap.put(groupName, scheduleJson);
                }
            }

            if (groupsToUpdate.isEmpty()) {
                System.out.println("No next day schedules to copy to main schedules.");
                return;
            }

            // Then, update the main schedules
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO groups (group_name, schedule_json, last_checked) VALUES (?, ?, ?)")) {
                for (String groupName : groupsToUpdate) {
                    ps.setString(1, groupName);
                    ps.setString(2, scheduleJsonMap.get(groupName));
                    ps.setLong(3, System.currentTimeMillis() / 1000L);
                    ps.addBatch();
                }
                int[] results = ps.executeBatch();
                System.out.println("Copied " + results.length + " next day schedules to main schedules.");
            }

            // Finally, clear the next day schedules
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM next_day_groups");
                System.out.println("Cleared next day schedules table.");
            }
        } catch (SQLException e) {
            System.err.println("Error copying next day schedules to main schedules: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public record GroupSchedule(String scheduleJson, long lastChecked) {}

    public List<Long> getUsersForGroup(String group) {
        List<Long> ids = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT u.chat_id FROM users u JOIN addresses a ON u.subscribed_address_key = a.address_key WHERE a.group_name = ?")) {
            ps.setString(1, group);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong("chat_id"));
            }
        } catch (SQLException e) {
            System.err.println("Error getting users for group: " + e.getMessage());
            e.printStackTrace();
        }
        return ids;
    }

    /**
     * Gets all users from the database
     * 
     * @return List of all user chat IDs
     */
    public List<Long> getAllUsers() {
        List<Long> ids = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT chat_id FROM users");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong("chat_id"));
            }
        } catch (SQLException e) {
            System.err.println("Error getting all users: " + e.getMessage());
            e.printStackTrace();
        }
        return ids;
    }

    public void clearWarnedFlagsForGroup(String group) {
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement("DELETE FROM warned_users WHERE address_key IN (SELECT address_key FROM addresses WHERE group_name = ?)")) {
            ps.setString(1, group);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    // User methods
    /**
     * Generic method to update a user property in the database
     * 
     * @param chatId The user's chat ID
     * @param columnName The column name to update
     * @param value The value to set
     */
    private void updateUserProperty(long chatId, String columnName, String value) {
        String sql = "INSERT OR REPLACE INTO users (chat_id, " + columnName + ") VALUES (?, ?) " +
                     "ON CONFLICT(chat_id) DO UPDATE SET " + columnName + " = excluded." + columnName;
        try (Connection conn = DriverManager.getConnection(dbUrl); 
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void setUserAddress(long chatId, String key) {
        updateUserProperty(chatId, "subscribed_address_key", key);
    }

    public void updateUserName(long chatId, String name) {
        updateUserProperty(chatId, "first_name", name);
    }

    /**
     * Gets the subscribed address key for a user.
     * 
     * @param chatId The chat ID of the user
     * @return The subscribed address key, or null if the user is not subscribed to any address
     */
    public String getUserSubscribedAddress(long chatId) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT subscribed_address_key FROM users WHERE chat_id = ?")) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("subscribed_address_key");
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting user's subscribed address: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    public List<Long> getUsersToWarn(String key, String time) {
        List<Long> ids = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT u.chat_id FROM users u LEFT JOIN warned_users w ON u.chat_id = w.chat_id AND w.address_key = ? AND w.outage_start_time = ? WHERE u.subscribed_address_key = ? AND w.id IS NULL")) {
            ps.setString(1, key);
            ps.setString(2, time);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong("chat_id"));
            }
        } catch (SQLException e) {
            System.err.println("Error getting users to warn: " + e.getMessage());
            e.printStackTrace();
        }
        return ids;
    }

    public void markUsersAsWarned(List<Long> ids, String key, String time) {
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO warned_users (chat_id, address_key, outage_start_time) VALUES (?, ?, ?)")) {
            for (Long id : ids) {
                ps.setLong(1, id);
                ps.setString(2, key);
                ps.setString(3, time);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    /**
     * Creates a backup of the database file.
     * Closes the current connection before backup and reopens it after.
     */
    public void backupDatabase() {
        String backupPath = databasePath + ".backup-" + System.currentTimeMillis();

        try {
            // Закриваємо з'єднання перед копіюванням
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }

            // Копіюємо файл бази даних
            Files.copy(Paths.get(databasePath), Paths.get(backupPath));
            System.out.println("[BACKUP] Database backed up to: " + backupPath);

            // Видаляємо старі резервні копії (залишаємо останні 5)
            cleanupOldBackups(5);

            // Відновлюємо з'єднання
            connection = DriverManager.getConnection(dbUrl);
        } catch (Exception e) {
            System.err.println("[BACKUP] Failed to backup database: " + e.getMessage());
        }
    }

    /**
     * Cleans up old database backups, keeping only the specified number of most recent backups.
     * 
     * @param keepCount Number of recent backups to keep
     */
    private void cleanupOldBackups(int keepCount) {
        try {
            File dir = new File(Paths.get(databasePath).getParent().toString());
            String baseName = Paths.get(databasePath).getFileName().toString();

            File[] backups = dir.listFiles((d, name) -> name.startsWith(baseName + ".backup-"));
            if (backups != null && backups.length > keepCount) {
                // Сортуємо за часом створення (від найновіших до найстаріших)
                Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());

                // Видаляємо старі резервні копії
                for (int i = keepCount; i < backups.length; i++) {
                    if (backups[i].delete()) {
                        System.out.println("[BACKUP] Deleted old backup: " + backups[i].getName());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[BACKUP] Error cleaning up old backups: " + e.getMessage());
        }
    }

    /**
     * Gets the size of the database file in bytes.
     * 
     * @return Size of the database file in bytes, or -1 if an error occurs
     */
    public long getDatabaseSize() {
        try {
            File dbFile = new File(databasePath);
            return dbFile.length();
        } catch (Exception e) {
            System.err.println("Error getting database size: " + e.getMessage());
            return -1;
        }
    }
}
