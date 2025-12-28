package ua.com.dtek.scraper.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Utility class for database operations.
 * Provides methods for executing database operations with consistent error handling.
 */
public class DatabaseUtil {

    private DatabaseUtil() {
        // Utility class, prevent instantiation
    }

    /**
     * Executes a database operation that returns a result.
     *
     * @param <T> The return type of the operation
     * @param connection The database connection
     * @param sql The SQL statement to execute
     * @param paramSetter A function that sets parameters on the prepared statement
     * @param resultMapper A function that maps the result set to the return type
     * @param defaultValue The default value to return if the operation fails
     * @param operationName A descriptive name for the operation (for logging)
     * @return The result of the operation, or the default value if the operation fails
     */
    public static <T> T executeQuery(Connection connection, String sql, 
                                    Consumer<PreparedStatement> paramSetter,
                                    Function<ResultSet, T> resultMapper, 
                                    T defaultValue, String operationName) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (paramSetter != null) {
                paramSetter.accept(ps);
            }
            
            try (ResultSet rs = ps.executeQuery()) {
                return resultMapper.apply(rs);
            }
        } catch (SQLException e) {
            System.err.println("[DATABASE ERROR] " + operationName + ": " + e.getMessage());
            e.printStackTrace();
            return defaultValue;
        }
    }

    /**
     * Executes a database update operation.
     *
     * @param connection The database connection
     * @param sql The SQL statement to execute
     * @param paramSetter A function that sets parameters on the prepared statement
     * @param operationName A descriptive name for the operation (for logging)
     * @return The number of rows affected, or -1 if the operation fails
     */
    public static int executeUpdate(Connection connection, String sql, 
                                   Consumer<PreparedStatement> paramSetter, 
                                   String operationName) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (paramSetter != null) {
                paramSetter.accept(ps);
            }
            
            return ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DATABASE ERROR] " + operationName + ": " + e.getMessage());
            e.printStackTrace();
            return -1;
        }
    }

    /**
     * Executes a database batch update operation.
     *
     * @param connection The database connection
     * @param sql The SQL statement to execute
     * @param batchSetter A function that sets parameters for each batch
     * @param operationName A descriptive name for the operation (for logging)
     * @return The number of rows affected in each batch, or null if the operation fails
     */
    public static int[] executeBatch(Connection connection, String sql, 
                                    Consumer<PreparedStatement> batchSetter, 
                                    String operationName) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (batchSetter != null) {
                batchSetter.accept(ps);
            }
            
            return ps.executeBatch();
        } catch (SQLException e) {
            System.err.println("[DATABASE ERROR] " + operationName + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}