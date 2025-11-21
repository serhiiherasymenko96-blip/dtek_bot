package ua.com.dtek.scraper.service;

import com.google.gson.Gson;
import ua.com.dtek.scraper.dto.Address;
import ua.com.dtek.scraper.dto.AddressInfo;
import ua.com.dtek.scraper.dto.TimeInterval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class DatabaseService {

    private final String dbUrl;
    private final Map<String, Address> monitoredAddresses;
    private final Gson gson = new Gson();

    public DatabaseService(String dbPath, Map<String, Address> addresses) {
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

        try (Connection conn = DriverManager.getConnection(dbUrl); Statement stmt = conn.createStatement()) {
            stmt.execute(sqlUsers);
            stmt.execute(sqlAddr);
            stmt.execute(sqlGroups);
            stmt.execute(sqlWarn);

            // Міграція
            try { stmt.execute("ALTER TABLE addresses ADD COLUMN group_name TEXT"); } catch (SQLException e) {}
            try { stmt.execute("ALTER TABLE addresses ADD COLUMN group_last_checked INTEGER NOT NULL DEFAULT 0"); } catch (SQLException e) {}

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

        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, groupCutoff);
            ps.setLong(2, cacheCutoff);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String key = rs.getString("address_key");
                if (monitoredAddresses.containsKey(key)) {
                    list.add(new AddressInfo(key, monitoredAddresses.get(key), rs.getString("group_name"), rs.getLong("group_last_checked")));
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public String getGroupForAddress(String key) {
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement("SELECT group_name FROM addresses WHERE address_key = ?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("group_name");
        } catch (SQLException e) { e.printStackTrace(); }
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

    public GroupSchedule getScheduleForGroup(String group) {
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement("SELECT schedule_json, last_checked FROM groups WHERE group_name = ?")) {
            ps.setString(1, group);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return new GroupSchedule(rs.getString("schedule_json"), rs.getLong("last_checked"));
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }
    public record GroupSchedule(String scheduleJson, long lastChecked) {}

    public List<Long> getUsersForGroup(String group) {
        List<Long> ids = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement("SELECT u.chat_id FROM users u JOIN addresses a ON u.subscribed_address_key = a.address_key WHERE a.group_name = ?")) {
            ps.setString(1, group);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) ids.add(rs.getLong("chat_id"));
        } catch (SQLException e) { e.printStackTrace(); }
        return ids;
    }

    public void clearWarnedFlagsForGroup(String group) {
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement("DELETE FROM warned_users WHERE address_key IN (SELECT address_key FROM addresses WHERE group_name = ?)")) {
            ps.setString(1, group);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    // User methods
    public void setUserAddress(long chatId, String key) {
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO users (chat_id, subscribed_address_key) VALUES (?, ?) ON CONFLICT(chat_id) DO UPDATE SET subscribed_address_key = excluded.subscribed_address_key")) {
            ps.setLong(1, chatId);
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void updateUserName(long chatId, String name) {
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO users (chat_id, first_name) VALUES (?, ?) ON CONFLICT(chat_id) DO UPDATE SET first_name = excluded.first_name")) {
            ps.setLong(1, chatId);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public List<Long> getUsersToWarn(String key, String time) {
        List<Long> ids = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement("SELECT u.chat_id FROM users u LEFT JOIN warned_users w ON u.chat_id = w.chat_id AND w.address_key = ? AND w.outage_start_time = ? WHERE u.subscribed_address_key = ? AND w.id IS NULL")) {
            ps.setString(1, key);
            ps.setString(2, time);
            ps.setString(3, key);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) ids.add(rs.getLong("chat_id"));
        } catch (SQLException e) { e.printStackTrace(); }
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
}