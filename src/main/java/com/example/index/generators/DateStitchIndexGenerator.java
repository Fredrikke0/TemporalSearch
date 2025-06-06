package com.example.index.generators;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.index.AnnotationType;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;

public final class DateStitchIndexGenerator extends AbstractUnigramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(DateStitchIndexGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_unigram_date";

    // For parsing normalized_ner (YYYY-MM-DD) and formatting to key (YYYYMMDD)
    private static final DateTimeFormatter INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern DATE_INPUT_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    public DateStitchIndexGenerator(
            IndexAccessInterface indexAccess,
            String stopwordsPath,
            Connection sqliteConn,
            ProgressTracker progress,
            int batchSize,
            Path customTempPath,
            SynonymManager sharedSynonymManager) throws IOException {
        super(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, customTempPath,
              AnnotationType.DATE,
              sharedSynonymManager);
        logger.info("DateStitchIndexGenerator initialized for index type: {}. Stopwords path: '{}'", MY_INDEX_NAME, stopwordsPath);
    }

    @Override
    protected boolean requiresSynonymIdForAnnotationValue() {
        return false; // Date strings (YYYYMMDD) are directly part of the key, not via synonym ID
    }

    private String normalizeDateToKeyFormat(String yyyyDashMmDashDd) {
        if (yyyyDashMmDashDd == null || !DATE_INPUT_PATTERN.matcher(yyyyDashMmDashDd).matches()) {
            logger.trace("Input date string '{}' does not match YYYY-MM-DD pattern for normalization to key format.", yyyyDashMmDashDd);
            return null;
        }
        try {
            LocalDate parsedDate = LocalDate.parse(yyyyDashMmDashDd, INPUT_FORMATTER);
            return parsedDate.format(KEY_FORMATTER); // Format to YYYYMMDD
        } catch (DateTimeParseException e) {
            logger.warn("Could not parse date string '{}' (expected YYYY-MM-DD) to LocalDate: {}", yyyyDashMmDashDd, e.getMessage());
            return null;
        }
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        // No-op for DATE stitch index when date string is directly in the key.
        // SynonymManager is not used for the date part.
        if (type != AnnotationType.DATE) {
            throw new IllegalArgumentException("DateStitchIndexGenerator's populateSpecificAnnotationSynonyms called with incorrect type: " + type);
        }
        logger.debug("populateSpecificAnnotationSynonyms is a no-op for DateStitchIndexGenerator as date strings are directly in the key.");
    }

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<AnnotationData> annotations = new ArrayList<>();
        String sql = """
            SELECT sentence_id, begin_char, end_char, ner, normalized_ner
            FROM annotations
            WHERE document_id = ?
                AND ner = 'DATE'
                AND normalized_ner IS NOT NULL AND normalized_ner != ''
            ORDER BY sentence_id, begin_char
        """;

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String nerTag = rs.getString("ner"); // Should always be "DATE"
                    String originalNormalizedDate = rs.getString("normalized_ner"); // Expected YYYY-MM-DD

                    if (!"DATE".equals(nerTag)) {
                         logger.warn("Fetched non-DATE NER tag '{}' for docId {} when expecting DATE. Normalized: '{}'. Skipping.", nerTag, documentId, originalNormalizedDate);
                         continue;
                    }

                    String dateKeyYYYYMMDD = normalizeDateToKeyFormat(originalNormalizedDate);
                    if (dateKeyYYYYMMDD == null) {
                        logger.trace("Skipping DATE annotation due to failed normalization of '{}' for doc ID {}.", originalNormalizedDate, documentId);
                        continue;
                    }

                    // For DATE stitch with direct date in key:
                    // annotationKeyComponent IS the YYYYMMDD string.
                    // specificValueForSynonym IS also the YYYYMMDD string (for consistency in AnnotationData, though not used for synonym lookup).
                    annotations.add(new AnnotationData(
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        dateKeyYYYYMMDD,         // annotationKeyComponent (e.g., "20231026")
                        dateKeyYYYYMMDD          // specificValueForSynonym (e.g., "20231026")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("SQLException in fetchAnnotationsForDocument for DATE stitch, doc ID {}: {}", documentId, e.getMessage(), e);
            throw e;
        }
        if (annotations.isEmpty()) {
            logger.trace("No valid DATE annotations found or all filtered for document ID {} for {} index.", documentId, MY_INDEX_NAME);
        } else {
            logger.trace("Fetched {} valid DATE annotations for document ID {} for {} index.", annotations.size(), documentId, MY_INDEX_NAME);
        }
        return annotations;
    }

    @Override
    protected String getSpecificAnnotationTypeDBCondition() {
        return "ner = 'DATE' AND normalized_ner IS NOT NULL AND normalized_ner != ''";
    }

    @Override
    protected AnnotationType getManagedAnnotationType() {
        return AnnotationType.DATE;
    }

    @Override
    public String getIndexName() {
        return MY_INDEX_NAME;
    }
}