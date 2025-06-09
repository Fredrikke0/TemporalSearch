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
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.index.AnnotationType;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;

public final class TrigramDateStitchGenerator extends AbstractNgramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(TrigramDateStitchGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_trigram_date";

    private static final DateTimeFormatter INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern DATE_INPUT_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    public TrigramDateStitchGenerator(
            IndexAccessInterface indexAccess,
            String stopwordsPath,
            Connection sqliteConn,
            ProgressTracker progress,
            int batchSize,
            Path customTempPath,
            SynonymManager sharedSynonymManager) throws IOException {
        super(3, // N-gram size (trigram)
              indexAccess,
              stopwordsPath,
              sqliteConn,
              progress,
              batchSize,
              customTempPath,
              sharedSynonymManager,
              AnnotationType.DATE);
        logger.info("TrigramDateStitchGenerator initialized for index type: {}. Stopwords path: '{}'", MY_INDEX_NAME, stopwordsPath);
    }

    @Override
    protected boolean requiresSynonymIdForAnnotationValue() {
        return false; // Date strings (YYYYMMDD) are directly part of the key
    }

    private String normalizeDateToKeyFormat(String yyyyDashMmDashDd) {
        if (yyyyDashMmDashDd == null || !DATE_INPUT_PATTERN.matcher(yyyyDashMmDashDd).matches()) {
            logger.trace("Input date string '{}' does not match YYYY-MM-DD pattern.", yyyyDashMmDashDd);
            return null;
        }
        String year = yyyyDashMmDashDd.substring(0, 4);
        if ("0000".equals(year)) {
            logger.debug("Invalid year '0000' in date string '{}'.", yyyyDashMmDashDd);
            return null;
        }
        try {
            LocalDate parsedDate = LocalDate.parse(yyyyDashMmDashDd, INPUT_FORMATTER);
            return parsedDate.format(KEY_FORMATTER);
        } catch (DateTimeParseException e) {
            logger.warn("Could not parse date string '{}': {}", yyyyDashMmDashDd, e.getMessage());
            return null;
        }
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        if (type != AnnotationType.DATE) {
            throw new IllegalArgumentException("TrigramDateStitchGenerator's populateSpecificAnnotationSynonyms called with incorrect type: " + type);
        }
        logger.debug("populateSpecificAnnotationSynonyms is a no-op for TrigramDateStitchGenerator.");
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
            logger.error("SQLException in fetchAnnotationsForDocument for Trigram DATE stitch, doc ID {}: {}", documentId, e.getMessage(), e);
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