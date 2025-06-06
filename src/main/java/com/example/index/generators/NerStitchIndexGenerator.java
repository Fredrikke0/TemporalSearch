package com.example.index.generators;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.index.AnnotationType;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ImmutableSet;

public final class NerStitchIndexGenerator extends AbstractUnigramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(NerStitchIndexGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_unigram_ner";

    // Define which NER types to exclude from the stitch index
    // These are typically noisy or less useful for unigram stitching, e.g., numerical types, misc.
    private static final Set<String> EXCLUDED_NER_TYPES = ImmutableSet.of(
        "NUMBER", "ORDINAL", "PERCENT", "MONEY", "DURATION", "SET", "MISC", "CAUSE_OF_DEATH", "CRIMINAL_CHARGE", "IDEOLOGY", "HANDLE"
    );

    // SQL fragment for excluding NER types
    private static final String NER_TYPES_TO_EXCLUDE_SQL_FRAGMENT = EXCLUDED_NER_TYPES.isEmpty() ? "1=1" :
        "ner NOT IN (" + EXCLUDED_NER_TYPES.stream().map(s -> "'" + s + "'").collect(Collectors.joining(", ")) + ")";

    public NerStitchIndexGenerator(
            IndexAccessInterface indexAccess,
            String stopwordsPath,
            Connection sqliteConn,
            ProgressTracker progress,
            int batchSize,
            Path customTempPath,
            SynonymManager sharedSynonymManager) throws IOException {
        super(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, customTempPath,
              AnnotationType.NER, sharedSynonymManager);
        logger.info("NerStitchIndexGenerator initialized for index type: {}. Stopwords path: '{}'", MY_INDEX_NAME, stopwordsPath);
        logger.debug("Excluding NER types: {}", EXCLUDED_NER_TYPES);
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        // No-op for NER.
        // The SynonymManager (shared) is used by the abstract class to get IDs for the normalized_ner values (specificValueForSynonym).
        // NER tags themselves (annotationKeyComponent) are not stored in the SynonymManager and don't need pre-loading here.
        if (type != AnnotationType.NER) {
            throw new IllegalArgumentException("NerStitchIndexGenerator's populateSpecificAnnotationSynonyms called with incorrect type: " + type);
        }
        logger.debug("populateSpecificAnnotationSynonyms is a no-op for NerStitchIndexGenerator as NER tags are not stored in SynonymManager and normalized_ner IDs are handled by the parent.");
    }

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<AnnotationData> annotations = new ArrayList<>();
        // Fetch NER types and their corresponding normalized_ner values for the given document.
        String sql = String.format("""
            SELECT sentence_id, begin_char, end_char, ner, normalized_ner
            FROM annotations
            WHERE document_id = ?
                AND ner IS NOT NULL AND ner != ''
                AND normalized_ner IS NOT NULL AND normalized_ner != ''
                AND %s
            ORDER BY sentence_id, begin_char
        """, NER_TYPES_TO_EXCLUDE_SQL_FRAGMENT);

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String nerTag = rs.getString("ner");
                    String normalizedNer = rs.getString("normalized_ner");

                    // Defensive checks, though SQL should handle most of this.
                    if (normalizedNer == null || normalizedNer.isEmpty() || nerTag == null || nerTag.isEmpty()) {
                        logger.trace("Skipping NER annotation due to null/empty normalized_ner or nerTag. Doc ID: {}, NER: '{}', Normalized: '{}'", documentId, nerTag, normalizedNer);
                        continue;
                    }

                    // For NER stitch:
                    // annotationKeyComponent IS the NER tag (e.g., "PERSON")
                    // specificValueForSynonym IS the normalized NER text (e.g., "john smith")
                    // The ID for "john smith" will be fetched by the parent class using synonymManager.
                    annotations.add(new AnnotationData(
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        nerTag.toUpperCase(),       // annotationKeyComponent (e.g., "PERSON")
                        normalizedNer.toLowerCase() // specificValueForSynonym (e.g., "john smith")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("SQLException in fetchAnnotationsForDocument for NER stitch, doc ID {}: {}", documentId, e.getMessage(), e);
            throw e; // Re-throw
        }
        if (annotations.isEmpty()) {
            logger.trace("No NER annotations found or all filtered for document ID {} for {} index.", documentId, MY_INDEX_NAME);
        } else {
            logger.trace("Fetched {} NER annotations for document ID {} for {} index.", annotations.size(), documentId, MY_INDEX_NAME);
        }
        return annotations;
    }

    @Override
    protected String getSpecificAnnotationTypeDBCondition() {
        // Used if parent class needs a generic filter for the annotation type.
        return "ner IS NOT NULL AND ner != '' AND normalized_ner IS NOT NULL AND normalized_ner != '' AND " + NER_TYPES_TO_EXCLUDE_SQL_FRAGMENT;
    }

    @Override
    protected AnnotationType getManagedAnnotationType() {
        return AnnotationType.NER;
    }

    @Override
    public String getIndexName() {
        return MY_INDEX_NAME;
    }
}