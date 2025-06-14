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

public final class BigramDateStitchGenerator extends AbstractNgramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(BigramDateStitchGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_bigram_date";

    private static final DateTimeFormatter INPUT_FORMAT_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter INPUT_FORMAT_YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter INPUT_FORMAT_YEAR = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    public BigramDateStitchGenerator(
            IndexAccessInterface indexAccess,
            String stopwordsPath,
            Connection sqliteConn,
            ProgressTracker progress,
            int batchSize,
            Path customTempPath,
            SynonymManager sharedSynonymManager) throws IOException {
        super(2, // N-gram size (bigram)
              indexAccess,
              stopwordsPath,
              sqliteConn,
              progress,
              batchSize,
              customTempPath,
              sharedSynonymManager,
              AnnotationType.DATE);
        logger.info("BigramDateStitchGenerator initialized for index type: {}. Stopwords path: '{}'", MY_INDEX_NAME, stopwordsPath);
    }

    @Override
    protected boolean requiresSynonymIdForAnnotationValue() {
        return false; // Date strings (YYYYMMDD) are directly part of the key
    }

    private String normalizeDateToKeyFormat(String date) {
        if (date == null || date.trim().isEmpty()) {
            logger.trace("Input date string is null or empty.");
            return null;
        }
        String trimmedDate = date.trim();

        if (trimmedDate.startsWith("0000")) {
            logger.debug("Invalid year '0000' in date string '{}'.", trimmedDate);
            return null;
        }

        LocalDate parsedDate;
        try {
            parsedDate = LocalDate.parse(trimmedDate, INPUT_FORMAT_FULL);
        } catch (DateTimeParseException e1) {
            try {
                java.time.YearMonth ym = java.time.YearMonth.parse(trimmedDate, INPUT_FORMAT_YEAR_MONTH);
                parsedDate = ym.atDay(1);
            } catch (DateTimeParseException e2) {
                try {
                    java.time.Year year = java.time.Year.parse(trimmedDate, INPUT_FORMAT_YEAR);
                    parsedDate = year.atDay(1);
                } catch (DateTimeParseException e3) {
                    logger.trace("Could not parse date string '{}' with available formats: {}", trimmedDate, e3.getMessage());
                    return null;
                }
            }
        }

        if (parsedDate.getYear() == 0) {
             logger.debug("Parsed date resulted in year 0000 for input '{}', which is invalid.", trimmedDate);
             return null;
        }
        return parsedDate.format(KEY_FORMATTER);
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        if (type != AnnotationType.DATE) {
            throw new IllegalArgumentException("BigramDateStitchGenerator's populateSpecificAnnotationSynonyms called with incorrect type: " + type);
        }
        logger.debug("populateSpecificAnnotationSynonyms is a no-op for BigramDateStitchGenerator.");
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
                    String originalNormalizedDate = rs.getString("normalized_ner");
                    String dateKeyYYYYMMDD = normalizeDateToKeyFormat(originalNormalizedDate);
                    if (dateKeyYYYYMMDD == null) {
                        logger.trace("Skipping DATE annotation due to failed normalization of '{}' for doc ID {}.", originalNormalizedDate, documentId);
                        continue;
                    }
                    annotations.add(new AnnotationData(
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        dateKeyYYYYMMDD, // annotationKeyComponent
                        dateKeyYYYYMMDD  // specificValueForSynonym (consistent, though not used for synonym lookup)
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("SQLException in fetchAnnotationsForDocument for Bigram DATE stitch, doc ID {}: {}", documentId, e.getMessage(), e);
            throw e;
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