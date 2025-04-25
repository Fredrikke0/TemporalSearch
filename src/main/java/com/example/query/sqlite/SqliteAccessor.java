package com.example.query.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides access to SQLite databases for different sources.
 * This class handles database connections and provides methods to retrieve metadata and other information.
 * Implemented as a singleton to avoid passing the index directory through all layers.
 */
public class SqliteAccessor {
    private static final Logger logger = LoggerFactory.getLogger(SqliteAccessor.class);
    
    // Singleton instance
    private static SqliteAccessor instance;
    
    // Base directory for all index sets
    private final String indexBaseDir;
    
    // Cache of database paths to avoid repeated file existence checks
    private final Map<String, String> dbPathCache = new HashMap<>();
    
    /**
     * Creates a new SqliteAccessor with the specified index base directory.
     * Private constructor to enforce singleton pattern.
     *
     * @param indexBaseDir The base directory for all index sets
     */
    private SqliteAccessor(String indexBaseDir) {
        this.indexBaseDir = indexBaseDir;
        logger.info("Initialized SqliteAccessor with base directory: {}", indexBaseDir);
    }
    
    /**
     * Initializes the singleton instance with the specified index base directory.
     * This should be called once at application startup.
     *
     * @param indexBaseDir The base directory for all index sets
     */
    public static synchronized void initialize(String indexBaseDir) {
        if (instance == null) {
            instance = new SqliteAccessor(indexBaseDir);
            logger.info("SqliteAccessor singleton initialized with base directory: {}", indexBaseDir);
        } else {
            logger.warn("SqliteAccessor already initialized, ignoring new initialization request");
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
     * Gets a connection to the SQLite database for the specified source.
     *
     * @param source The source name (e.g., "wikipedia")
     * @return A connection to the database
     * @throws SQLException If a database access error occurs
     */
    public Connection getConnection(String source) throws SQLException {
        String dbPath = getDatabasePath(source);
        //logger.debug("Opening connection to database: {}", dbPath);
        
        try {
            Class.forName("org.sqlite.JDBC");
            return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        } catch (ClassNotFoundException e) {
            logger.error("SQLite JDBC driver not found", e);
            throw new SQLException("SQLite JDBC driver not found", e);
        }
    }
    
    /**
     * Gets metadata for a document from the specified source.
     *
     * @param source The source name
     * @param documentId The document ID
     * @param fieldName The metadata field name, or null to get all fields
     * @return The metadata value, or a JSON-like string with all metadata if fieldName is null
     */
    public String getMetadata(String source, int documentId, String fieldName) {
        String value = null;
        
        try (Connection conn = getConnection(source)) {
            // Query to get metadata from the database
            String sql;
            if (fieldName == null) {
                // Get all metadata fields (excluding the text field to avoid large data)
                sql = "SELECT document_id, title, timestamp FROM documents WHERE document_id = ?";
            } else {
                // Get specific metadata field
                sql = "SELECT " + fieldName + " FROM documents WHERE document_id = ?";
            }
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, documentId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        if (fieldName == null) {
                            // Build a JSON-like string with all metadata
                            StringBuilder sb = new StringBuilder("{");
                            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                                String colName = rs.getMetaData().getColumnName(i);
                                String colValue = rs.getString(i);
                                if (i > 1) sb.append(", ");
                                sb.append("\"").append(colName).append("\": \"")
                                  .append(colValue != null ? colValue : "").append("\"");
                            }
                            sb.append("}");
                            value = sb.toString();
                        } else {
                            // Get specific field value
                            value = rs.getString(1);
                        }
                    }
                }
            }
            
            //logger.debug("Retrieved metadata for document {} from {}: {}", documentId, source, value);
        } catch (Exception e) {
            // Log error and continue
            logger.error("Error getting metadata for document {} from {}: {}", 
                        documentId, source, e.getMessage(), e);
            value = null;
        }
        
        return value;
    }
    
    /**
     * Gets the document text from the specified source.
     *
     * @param source The source name
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
                        text = rs.getString(1);
                    }
                }
            }
            
            logger.debug("Retrieved text for document {} from {}, length: {}", 
                        documentId, source, text != null ? text.length() : 0);
        } catch (Exception e) {
            // Log error and continue
            logger.error("Error getting text for document {} from {}: {}", 
                        documentId, source, e.getMessage(), e);
            text = null;
        }
        
        return text;
    }
    
    /**
     * Gets the path to the SQLite database for the specified source.
     *
     * @param source The source name
     * @return The path to the database
     */
    private String getDatabasePath(String source) {
        // Check if we've already resolved this path
        if (dbPathCache.containsKey(source)) {
            return dbPathCache.get(source);
        }
        
        // Use the source name for both directory and file
        String path = indexBaseDir + "/" + source + "/" + source + ".db";
        dbPathCache.put(source, path);
        return path;
    }
} 