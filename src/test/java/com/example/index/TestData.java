package com.example.index;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.nio.file.Path;
import java.sql.DriverManager;

/**
 * Utility class for creating test data for index tests.
 * Provides methods to create both annotation and dependency entries,
 * as well as methods to insert test data into the database.
 */
public final class TestData {

    public static AnnotationEntry createAnnotation(int annotationId, int docId, String token, String pos, String ner, String normalizedNer) {
        return new AnnotationEntry(
            annotationId,
            docId,
            1, // sentence_id
            0, // begin_char
            token.length(), // end_char
            token,
            pos,
            ner,
            normalizedNer
        );
    }
    public static AnnotationEntry createAnnotation(int annotationId, int docId, String token, String pos) {
        return new AnnotationEntry(
            annotationId, docId, 1, 0, token.length(),
            token, pos,
            null, // ner
            null // normalizedNer
        );
    }
     public static AnnotationEntry createAnnotation(int docId, String token, String pos) {
        return createAnnotation(1, docId, token, pos);
    }

    public static DependencyEntry createDependency(int docId, String head, String dependent, String relation) {
        return new DependencyEntry(
            0, // Placeholder for dependencyId, adjust if tests need specific values
            docId, 1, 0, head.length() + dependent.length() + 1,
            head, dependent, relation
        );
    }

    public static void createTestDatabase(Path dbPath) throws SQLException {
        String url = "jdbc:sqlite:" + dbPath.toString();
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO annotations (annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {

            // Basic test data: annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner
            Object[][] data = {
                {1, 1, 1, 0, 2, "He", "PRP", null, null},
                {2, 1, 1, 3, 5, "is", "VBZ", null, null},
                {3, 1, 1, 6, 10, "good", "JJ", null, null},
                {4, 2, 1, 0, 4, "This", "DT", null, null},
                {5, 2, 1, 5, 7, "is", "VBZ", null, null},
                {6, 2, 1, 8, 15, "another", "DT", null, null},
                {7, 2, 1, 16, 24, "sentence", "NN", null, null},
                {8, 2, 1, 25, 25, ".", ".", null, null},
                // For NER tests
                {9, 3, 1, 0, 4, "John", "NNP", "PERSON", null},
                {10, 3, 1, 5, 9, "Doe", "NNP", "PERSON", null},
                {11, 3, 1, 10, 13, "flew", "VBD", "O", null},
                {12, 3, 1, 14, 16, "to", "TO", "O", null},
                {13, 3, 1, 17, 20, "Paris", "NNP", "LOCATION", null},
                 // For DATE tests
                {14, 4, 1, 0, 1, "On", "IN", "O", null},
                {15, 4, 1, 2, 6, "2023", "CD", "DATE", "2023"},
                {16, 4, 1, 6, 7, "-", "HYPH", "DATE", "2023"},
                {17, 4, 1, 7, 9, "01", "CD", "DATE", "2023-01"},
                {18, 4, 1, 9, 10, "-", "HYPH", "DATE", "2023-01"},
                {19, 4, 1, 10, 12, "15", "CD", "DATE", "2023-01-15"},
                {20, 4, 1, 13, 16, "he", "PRP", "O", null},
                {21, 4, 1, 17, 21, "left", "VBD", "O", null}
            };

            for (Object[] row : data) {
                for (int i = 0; i < row.length; i++) {
                    stmt.setObject(i + 1, row[i]);
                }
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    public static void insertBasicAnnotations(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO annotations (annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            // Basic test data: annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner
            Object[][] data = {
                {1, 1, 1, 0, 2, "He", "PRP", null, null},
                {2, 1, 1, 3, 5, "is", "VBZ", null, null},
                {3, 1, 1, 6, 10, "good", "JJ", null, null}
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

    // Sample data for testing
    public static final AnnotationEntry ANNOTATION_ENTRY_1 = new AnnotationEntry(1, 1, 1, 0, 2, "He", "PRP", null, null);
    public static final AnnotationEntry ANNOTATION_ENTRY_2 = new AnnotationEntry(2, 1, 1, 3, 5, "is", "VBZ", null, null);
    public static final AnnotationEntry ANNOTATION_ENTRY_3 = new AnnotationEntry(3, 1, 1, 6, 10, "good", "JJ", null, null);
}