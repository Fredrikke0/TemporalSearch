package com.example.query.snippet;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Utility class for setting up a mock database for testing
 */
public class MockDatabaseSetup {

    /**
     * Creates an in-memory SQLite database with test data
     * @return Connection to the in-memory database
     * @throws SQLException if a database error occurs
     */
    public static Connection setupMockDatabase() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");

        // Create tables
        try (Statement stmt = connection.createStatement()) {
            // Create documents table
            stmt.execute("""
                CREATE TABLE documents (
                    document_id INTEGER PRIMARY KEY,
                    title TEXT,
                    text TEXT,
                    timestamp TEXT
                )
            """);

            // Create annotations table
            stmt.execute("""
                CREATE TABLE annotations (
                    annotation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    document_id INTEGER NOT NULL,
                    sentence_id INTEGER,
                    begin_char INTEGER,
                    end_char INTEGER,
                    token TEXT,
                    lemma TEXT,
                    pos TEXT,
                    ner TEXT,
                    normalized_ner TEXT
                )
            """);
        }

        // Insert test data
        insertTestDocument(connection);
        insertTestAnnotations(connection);

        return connection;
    }

    private static void insertTestDocument(Connection connection) throws SQLException {
        String sql = "INSERT INTO documents (document_id, title, text, timestamp) VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, 1);
            stmt.setString(2, "Test Document");
            stmt.setString(3, "This is the first sentence. This is the second sentence with a match. And this is the third sentence.");
            stmt.setString(4, "2023-01-01T00:00:00Z");
            stmt.executeUpdate();
        }
    }

    private static void insertTestAnnotations(Connection connection) throws SQLException {
        // First sentence tokens
        insertAnnotation(connection, 1, 0, 0, 4, "This", "O");
        insertAnnotation(connection, 1, 0, 5, 7, "is", "O");
        insertAnnotation(connection, 1, 0, 8, 11, "the", "O");
        insertAnnotation(connection, 1, 0, 12, 17, "first", "ORDINAL");
        insertAnnotation(connection, 1, 0, 18, 26, "sentence", "O");
        insertAnnotation(connection, 1, 0, 26, 27, ".", "O");

        // Second sentence tokens
        insertAnnotation(connection, 1, 1, 28, 32, "This", "O");
        insertAnnotation(connection, 1, 1, 33, 35, "is", "O");
        insertAnnotation(connection, 1, 1, 36, 39, "the", "O");
        insertAnnotation(connection, 1, 1, 40, 46, "second", "ORDINAL");
        insertAnnotation(connection, 1, 1, 47, 55, "sentence", "O");
        insertAnnotation(connection, 1, 1, 56, 60, "with", "O");
        insertAnnotation(connection, 1, 1, 61, 62, "a", "O");
        insertAnnotation(connection, 1, 1, 63, 68, "match", "O");
        insertAnnotation(connection, 1, 1, 68, 69, ".", "O");

        // Third sentence tokens
        insertAnnotation(connection, 1, 2, 70, 73, "And", "O");
        insertAnnotation(connection, 1, 2, 74, 78, "this", "O");
        insertAnnotation(connection, 1, 2, 79, 81, "is", "O");
        insertAnnotation(connection, 1, 2, 82, 85, "the", "O");
        insertAnnotation(connection, 1, 2, 86, 91, "third", "ORDINAL");
        insertAnnotation(connection, 1, 2, 92, 100, "sentence", "O");
        insertAnnotation(connection, 1, 2, 100, 101, ".", "O");
    }

    private static void insertAnnotation(Connection connection, long documentId, int sentenceId,
                                        int beginChar, int endChar, String token, String ner) throws SQLException {
        String sql = """
            INSERT INTO annotations
            (document_id, sentence_id, begin_char, end_char, token, ner)
            VALUES (?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, documentId);
            stmt.setInt(2, sentenceId);
            stmt.setInt(3, beginChar);
            stmt.setInt(4, endChar);
            stmt.setString(5, token);
            stmt.setString(6, ner);
            stmt.executeUpdate();
        }
    }
}