package com.example.index.generators;

import com.example.index.AnnotationType;
import com.example.logging.ProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class DateStitchIndexGenerator extends AbstractUnigramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(DateStitchIndexGenerator.class);
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final String MY_INDEX_NAME = "stitch-date";

    public DateStitchIndexGenerator(
            String indexBaseDir,
            String stopwordsPath,
            Connection sqliteConn,
            ProgressTracker progress,
            int batchSize,
            Path customTempPath) throws IOException {
        super(indexBaseDir, MY_INDEX_NAME, stopwordsPath, sqliteConn, progress, batchSize, customTempPath,
              AnnotationType.DATE
        );
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        if (type != AnnotationType.DATE) {
            throw new IllegalArgumentException("DateStitchIndexGenerator can only populate DATE synonyms.");
        }
        String query = """
            SELECT DISTINCT normalized_ner
            FROM annotations
            WHERE ner = 'DATE'
                AND normalized_ner IS NOT NULL
                AND normalized_ner LIKE '____-__-__'
            ORDER BY normalized_ner
        """;

        int count = 0;
        int skipped = 0;
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String dateValue = rs.getString(1);
                if (dateValue != null && DATE_PATTERN.matcher(dateValue).matches()) {
                    try {
                        LocalDate.parse(dateValue); // Validate date string
                        annotationSynonyms.getOrCreateId(dateValue);
                        count++;
                    } catch (DateTimeParseException e) {
                        logger.debug("Filtered out invalid date format: {} (not a valid date)", dateValue);
                        skipped++;
                    } catch (IllegalArgumentException e) {
                        logger.warn("Skipping invalid DATE annotation during synonym population: {}", e.getMessage());
                        skipped++;
                    }
                } else if (dateValue != null) {
                    logger.debug("Filtered out invalid date string: {} (does not match YYYY-MM-DD pattern)", dateValue);
                    skipped++;
                }
            }
        }
        if (skipped > 0) {
            logger.info("Populated {} DATE synonyms, filtered out {} invalid values", count, skipped);
        } else {
            logger.info("Populated {} DATE synonyms", count);
        }
        annotationSynonyms.validateSynonyms();
    }

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<AnnotationData> annotations = new ArrayList<>();
        String sql = """
            SELECT sentence_id, begin_char, end_char, normalized_ner
            FROM annotations
            WHERE document_id = ?
                AND ner = 'DATE'
                AND normalized_ner IS NOT NULL
                AND normalized_ner LIKE '____-__-__'
        """;

        // High-verbosity logging for specific document diagnosis
        boolean detailedLogging = (documentId == 1); // Log for doc 1 in date-stitch
        if (detailedLogging) {
            logger.info("AUDIT_FETCH_ANNOTATIONS [{}]: Starting fetch for docId: {}", getIndexName(), documentId);
        }

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String dateValue = rs.getString("normalized_ner");
                    int sentenceId = rs.getInt("sentence_id");
                    int beginChar = rs.getInt("begin_char");
                    int endChar = rs.getInt("end_char");

                    if (detailedLogging) {
                        logger.info("AUDIT_FETCH_ANNOTATIONS [{}]: DocId: {}, Found potential DATE: value='{}', sentId={}, begin={}, end={}",
                                    getIndexName(), documentId, dateValue, sentenceId, beginChar, endChar);
                    }

                    if (dateValue != null && DATE_PATTERN.matcher(dateValue).matches()) {
                        try {
                            LocalDate.parse(dateValue); // Final check
                            AnnotationData ad = new AnnotationData(
                                    sentenceId,
                                    beginChar,
                                    endChar,
                                    dateValue
                            );
                            annotations.add(ad);
                            if (detailedLogging) {
                                logger.info("AUDIT_FETCH_ANNOTATIONS [{}]: DocId: {}, ADDED AnnotationData: {}", getIndexName(), documentId, ad);
                            }
                        } catch (DateTimeParseException e) {
                            if (detailedLogging) {
                                logger.warn("AUDIT_FETCH_ANNOTATIONS [{}]: DocId: {}, SKIPPING invalid date value (parse failed): '{}' for doc {}",
                                            getIndexName(), documentId, dateValue, documentId, e);
                            } else {
                                logger.debug("Skipping invalid date value during fetch: {} for doc {} - {}", dateValue, documentId, e.getMessage());
                            }
                        }
                    } else {
                        if (detailedLogging) {
                            logger.info("AUDIT_FETCH_ANNOTATIONS [{}]: DocId: {}, SKIPPING date value (null or pattern mismatch): '{}' for doc {}",
                                        getIndexName(), documentId, dateValue, documentId);
                        }
                    }
                }
            }
        }
        if (detailedLogging) {
            logger.info("AUDIT_FETCH_ANNOTATIONS [{}]: Finished fetch for docId: {}. Annotations found: {}",
                        getIndexName(), documentId, annotations.size());
        }
        return annotations;
    }

    @Override
    protected AnnotationType getManagedAnnotationType() {
        return AnnotationType.DATE;
    }

    @Override
    public String getIndexName() {
        return MY_INDEX_NAME;
    }

    @Override
    protected String getSpecificAnnotationTypeDBCondition() {
        return "ner = 'DATE' AND normalized_ner IS NOT NULL AND normalized_ner LIKE '____-__-__'";
    }
} 