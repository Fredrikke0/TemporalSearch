package com.example.index.generators.stitch;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.index.AnnotationType;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;

public final class UnigramDateStitchIndexGenerator extends AbstractNgramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(UnigramDateStitchIndexGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_unigram_date";

    // For parsing normalized_ner (YYYY-MM-DD) and formatting to key (YYYYMMDD)
    private static final DateTimeFormatter INPUT_FORMAT_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter INPUT_FORMAT_YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter INPUT_FORMAT_YEAR = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public UnigramDateStitchIndexGenerator(
            IndexAccessInterface indexAccess,
            String stopwordsPath,
            Connection sqliteConn,
            ProgressTracker progress,
            int batchSize,
            Path customTempPath,
            SynonymManager sharedSynonymManager) throws IOException {
        super(1,
              indexAccess,
              stopwordsPath,
              sqliteConn,
              progress,
              batchSize,
              customTempPath,
              sharedSynonymManager,
              AnnotationType.DATE);
        logger.info("UnigramDateStitchIndexGenerator initialized for index type: {}. Stopwords path: '{}'", MY_INDEX_NAME, stopwordsPath);
    }

    @Override
    protected boolean requiresSynonymIdForAnnotationValue() {
        return false; // Date strings (YYYYMMDD) are directly part of the key, not via synonym ID
    }

    private String normalizeDateToKeyFormat(String date) {
        if (date == null || date.trim().isEmpty()) {
            logger.trace("Input date string is null or empty.");
            return null;
        }
        String trimmedDate = date.trim();

        // Check for "0000" year early, as it's invalid for LocalDate
        if (trimmedDate.startsWith("0000")) {
            logger.debug("Invalid year '0000' in date string '{}'. Dates with year 0000 are not supported.", trimmedDate);
            return null;
        }

        LocalDate parsedDate;
        try {
            // Attempt to parse as YYYY-MM-DD
            parsedDate = LocalDate.parse(trimmedDate, INPUT_FORMAT_FULL);
        } catch (DateTimeParseException e1) {
            try {
                // Attempt to parse as YYYY-MM
                java.time.YearMonth ym = java.time.YearMonth.parse(trimmedDate, INPUT_FORMAT_YEAR_MONTH);
                parsedDate = ym.atDay(1); // Normalize to the first day of that month
            } catch (DateTimeParseException e2) {
                try {
                    // Attempt to parse as YYYY
                    java.time.Year year = java.time.Year.parse(trimmedDate, INPUT_FORMAT_YEAR);
                    parsedDate = year.atDay(1); // Normalize to the first day of that year
                } catch (DateTimeParseException e3) {
                    logger.warn("Could not parse date string '{}' with available formats (YYYY-MM-DD, YYYY-MM, YYYY): {}", trimmedDate, e3.getMessage());
                    return null;
                }
            }
        }

        // Check again for year 0000 after parsing attempts
        if (parsedDate.getYear() == 0) {
             logger.debug("Parsed date resulted in year 0000 for input '{}', which is invalid.", trimmedDate);
             return null;
        }

        return parsedDate.format(KEY_FORMAT);
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
    protected AnnotationType getManagedAnnotationType() {
        return AnnotationType.DATE;
    }

    @Override
    public String getIndexName() {
        return MY_INDEX_NAME;
    }
}