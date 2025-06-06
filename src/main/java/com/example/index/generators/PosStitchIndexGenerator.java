package com.example.index.generators;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.index.AnnotationType;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;

public final class PosStitchIndexGenerator extends AbstractUnigramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(PosStitchIndexGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_unigram_pos";

    // SQL fragment to exclude common punctuation and symbols for POS.
    // Now directly references the list from POSIndexGenerator for consistency.
    public static final String POS_TAGS_TO_EXCLUDE_SQL_FRAGMENT = "pos NOT IN " + POSIndexGenerator.POS_TAGS_TO_EXCLUDE_SQL;

    public PosStitchIndexGenerator(
            IndexAccessInterface indexAccess,
            String stopwordsPath,
            Connection sqliteConn,
            ProgressTracker progress,
            int batchSize,
            Path customTempPath,
            SynonymManager sharedSynonymManager) throws IOException {
        super(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, customTempPath,
              AnnotationType.POS, sharedSynonymManager
        );
        logger.info("PosStitchIndexGenerator initialized for index type: {}. Stopwords path: '{}'", MY_INDEX_NAME, stopwordsPath);
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        // No-op for POS.
        // The SynonymManager (shared) is used by the abstract class to get IDs for the actual tokens (specificValueForSynonym).
        // POS tags themselves (annotationKeyComponent) are not stored in the SynonymManager and don't need pre-loading here.
        if (type != AnnotationType.POS) {
            // This check is more of a safeguard, as the constructor passes AnnotationType.POS.
            throw new IllegalArgumentException("PosStitchIndexGenerator's populateSpecificAnnotationSynonyms called with incorrect type: " + type);
        }
        logger.debug("populateSpecificAnnotationSynonyms is a no-op for PosStitchIndexGenerator as POS tags are not stored in SynonymManager and token IDs are handled by the parent.");
    }

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<AnnotationData> annotations = new ArrayList<>();
        // Fetch POS tags and their corresponding tokens for the given document.
        String sql = String.format("""
            SELECT sentence_id, begin_char, end_char, token, pos
            FROM annotations
            WHERE document_id = ?
                AND pos IS NOT NULL AND pos != ''
                AND token IS NOT NULL AND token != ''
                AND %s
            ORDER BY sentence_id, begin_char
        """, POS_TAGS_TO_EXCLUDE_SQL_FRAGMENT); // Use the local constant

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String posTag = rs.getString("pos");
                    String token = rs.getString("token");

                    // Basic null/empty checks already in SQL, but good to have defense here too.
                    if (token == null || token.isEmpty() || posTag == null || posTag.isEmpty()) {
                        logger.trace("Skipping annotation due to null/empty token or posTag after SQL query. Doc ID: {}, Token: '{}', POS: '{}'", documentId, token, posTag);
                        continue;
                    }

                    // For POS stitch:
                    // annotationKeyComponent IS the POS tag (e.g., "NNP")
                    // specificValueForSynonym IS the actual token (e.g., "smith")
                    // The ID for "smith" will be fetched by the parent class using synonymManager.
                    annotations.add(new AnnotationData(
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        posTag.toUpperCase(),    // annotationKeyComponent (e.g., "NNP")
                        token.toLowerCase()      // specificValueForSynonym (e.g., "smith")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("SQLException in fetchAnnotationsForDocument for POS stitch, doc ID {}: {}", documentId, e.getMessage(), e);
            throw e; // Re-throw to allow parent class to handle if necessary
        }
        if (annotations.isEmpty()) {
            logger.trace("No POS annotations found or all filtered for document ID {} for {} index.", documentId, MY_INDEX_NAME);
        } else {
            logger.trace("Fetched {} POS annotations for document ID {} for {} index.", annotations.size(), documentId, MY_INDEX_NAME);
        }
        return annotations;
    }

    @Override
    protected String getSpecificAnnotationTypeDBCondition() {
        // This condition is used if the parent class needs a generic filter for the annotation type.
        // For fetchAnnotationsForDocument, the query is self-contained.
        return "pos IS NOT NULL AND pos != '' AND " + POS_TAGS_TO_EXCLUDE_SQL_FRAGMENT;
    }

    @Override
    public String getIndexName() {
        return MY_INDEX_NAME;
    }

    @Override
    protected AnnotationType getManagedAnnotationType() {
        return AnnotationType.POS;
    }
}
