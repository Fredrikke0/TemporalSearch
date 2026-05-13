package com.example.query.sqlite;

import com.example.util.TextCompression;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides access to SQLite databases for different sources.
 * This class handles database connections and provides methods to retrieve
 * metadata and other information.
 * Implemented as a singleton. QueryCLI re-initializes it with the specific DB
 * path for each query.
 */
public class SqliteAccessor {
    private static final Logger logger = LoggerFactory.getLogger(SqliteAccessor.class);

    private static SqliteAccessor instance;

    private String currentDbFilePath;

    private final Map<String, String> dbPathCache = new HashMap<>();

    /**
     * Private constructor for singleton.
     *
     * @param dbFilePath The direct path to the SQLite database file.
     */
    private SqliteAccessor(String dbFilePath) {
        this.currentDbFilePath = dbFilePath;
        logger.info("SqliteAccessor instance created with database path: {}", dbFilePath);
    }

    /**
     * Initializes or re-initializes the singleton instance with the specified
     * database file path.
     * QueryCLI calls this for each query to set the correct database context.
     *
     * @param dbFilePath The direct path to the SQLite database file.
     */
    public static synchronized void initialize(String dbFilePath) {
        if (instance == null) {
            instance = new SqliteAccessor(dbFilePath);
            logger.info("SqliteAccessor singleton initialized with database path: {}", dbFilePath);
        } else {
            logger.info("Re-initializing SqliteAccessor. Old DB path: '{}', New DB path: '{}'",
                    instance.currentDbFilePath, dbFilePath);
            instance.currentDbFilePath = dbFilePath;
            instance.dbPathCache.clear();
            logger.info("SqliteAccessor re-initialized and cache cleared. Current DB path: {}",
                    instance.currentDbFilePath);
        }
    }

    /**
     * Gets the singleton instance of SqliteAccessor.
     * The instance must be initialized first with initialize().
     *
     * @return The singleton instance
     * @throws IllegalStateException if the instance has not been initialized
     */
    public static synchronized SqliteAccessor getInstance() {
        if (instance == null) {
            throw new IllegalStateException("SqliteAccessor has not been initialized. Call initialize() first.");
        }
        return instance;
    }

    /**
     * Gets a connection to the currently configured SQLite database.
     *
     * @param source The source name (used for logging and consistency, path comes
     *               from initialization)
     * @return A connection to the database
     * @throws SQLException If a database access error occurs
     */
    public Connection getConnection(String source) throws SQLException {
        String dbPath = getDatabasePath(source);

        try {
            Class.forName("org.sqlite.JDBC");
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");
                stmt.execute("PRAGMA temp_store=MEMORY;");
                stmt.execute("PRAGMA cache_size=-1000000;"); // 1GB cache
            }
            return conn;
        } catch (ClassNotFoundException e) {
            logger.error("SQLite JDBC driver not found", e);
            throw new SQLException("SQLite JDBC driver not found", e);
        }
    }

    /**
     * Gets metadata for a document from the specified source.
     *
     * @param source     The source name
     * @param documentId The document ID
     * @param fieldName  The metadata field name, or null to get all fields
     * @return The metadata value, or a JSON-like string with all metadata if
     *         fieldName is null
     */
    public String getMetadata(String source, int documentId, String fieldName) {
        String value = null;

        try (Connection conn = getConnection(source)) {
            String sql;
            if (fieldName == null) {
                sql = "SELECT document_id, title, timestamp FROM documents WHERE document_id = ?";
            } else {
                sql = "SELECT " + fieldName + " FROM documents WHERE document_id = ?";
            }

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, documentId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        if (fieldName == null) {
                            StringBuilder sb = new StringBuilder("{");
                            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                                String colName = rs.getMetaData().getColumnName(i);
                                String colValue = rs.getString(i);
                                if (i > 1)
                                    sb.append(", ");
                                sb.append("\"").append(colName).append("\": \"")
                                        .append(colValue != null ? colValue : "").append("\"");
                            }
                            sb.append("}");
                            value = sb.toString();
                        } else {
                            value = rs.getString(1);
                        }
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Error getting metadata for document {} from {}: {}",
                    documentId, source, e.getMessage(), e);
            value = null;
        }

        return value;
    }

    /**
     * Gets the document text from the specified source.
     *
     * @param source     The source name
     * @param documentId The document ID
     * @return The document text, or null if not found
     */
    public String getDocumentText(String source, int documentId) {
        String text = null;

        try (Connection conn = getConnection(source)) {
            String sql = "SELECT text FROM documents WHERE document_id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, documentId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        byte[] compressed = rs.getBytes(1);
                        text = TextCompression.decompress(compressed);
                    }
                }
            }

            logger.debug("Retrieved text for document {} from {}, length: {}",
                    documentId, source, text != null ? text.length() : 0);
        } catch (Exception e) {
            logger.error("Error getting text for document {} from {}: {}",
                    documentId, source, e.getMessage(), e);
            text = null;
        }

        return text;
    }

    /**
     * Gets the path to the SQLite database for the specified source.
     * Relies on currentDbFilePath being set correctly by initialize().
     *
     * @param source The source name (used as a key for caching currentDbFilePath)
     * @return The path to the database
     */
    private String getDatabasePath(String source) {
        // Check cache first.
        if (dbPathCache.containsKey(source)) {
            return dbPathCache.get(source);
        }

        // The path is simply what was provided during the last initialization.
        String actualDbPath = this.currentDbFilePath;

        // Sanity check: ensure the initialized path is not null or empty
        if (actualDbPath == null || actualDbPath.trim().isEmpty()) {
            logger.error(
                    "SqliteAccessor: currentDbFilePath is null or empty for source '{}'. This indicates an issue with initialization.",
                    source);
            throw new IllegalStateException("SqliteAccessor currentDbFilePath is not set for source: " + source);
        }

        dbPathCache.put(source, actualDbPath);
        logger.debug("SqliteAccessor: Using database path '{}' for source '{}' (from initialization). Path cached.",
                actualDbPath, source);
        return actualDbPath;
    }
}
