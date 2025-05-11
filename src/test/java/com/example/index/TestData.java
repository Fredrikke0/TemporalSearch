package com.example.index;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * Utility class for creating test data for index tests.
 * Provides methods to create both annotation and dependency entries,
 * as well as methods to insert test data into the database.
 */
public class TestData {
    
    public static AnnotationEntry createAnnotation(int docId, String lemma, String pos) {
        // Default annotation id to 1 for backward compatibility
        return createAnnotation(1, docId, lemma, pos);
    }

    public static AnnotationEntry createAnnotation(int annotationId, int docId, String lemma, String pos) {
        return new AnnotationEntry(
            annotationId, docId, 1, 0, lemma.length(),
            lemma, pos, LocalDate.now()
        );
    }

    public static DependencyEntry createDependency(int docId, String head, String dependent, String relation) {
        return new DependencyEntry(
            0, // Placeholder for dependencyId, adjust if tests need specific values
            docId, 1, 0, head.length() + dependent.length() + 1,
            head, dependent, relation, LocalDate.now()
        );
    }

    public static void insertBasicAnnotations(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO annotations (annotation_id, document_id, sentence_id, begin_char, end_char, lemma, pos) VALUES (?, ?, ?, ?, ?, ?, ?)"
        )) {
            // Basic test data
            Object[][] data = {
                {1, 1, 1, 0, 3, "cat", "NOUN"},
                {2, 1, 1, 4, 10, "chases", "VERB"},
                {3, 1, 1, 11, 16, "mouse", "NOUN"}
            };

            for (Object[] row : data) {
                for (int i = 0; i < row.length; i++) {
                    stmt.setObject(i + 1, row[i]);
                }
                stmt.executeUpdate();
            }
        }
    }

    public static void insertBasicDependencies(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO dependencies (document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation) VALUES (?, ?, ?, ?, ?, ?, ?)"
        )) {
            // Basic test data
            Object[][] data = {
                {1, 1, 0, 10, "chases", "cat", "nsubj"},
                {1, 1, 4, 16, "chases", "mouse", "dobj"}
            };

            for (Object[] row : data) {
                for (int i = 0; i < row.length; i++) {
                    stmt.setObject(i + 1, row[i]);
                }
                stmt.executeUpdate();
            }
        }
    }

    public static void insertDocument(Connection conn, int documentId, LocalDate timestamp) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)"
        )) {
            stmt.setInt(1, documentId);
            stmt.setString(2, timestamp.toString());
            stmt.executeUpdate();
        }
    }
} 