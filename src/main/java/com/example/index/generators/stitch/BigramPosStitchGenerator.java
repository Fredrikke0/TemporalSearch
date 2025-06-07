package com.example.index.generators.stitch;

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
import com.example.index.generators.POSIndexGenerator; // For POS_TAGS_TO_EXCLUDE_SQL
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;

public final class BigramPosStitchGenerator extends AbstractNgramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(BigramPosStitchGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_bigram_pos";

    public BigramPosStitchGenerator(
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
              AnnotationType.POS);
        logger.info("BigramPosStitchGenerator initialized for index type: {}. Stopwords path: '{}'", MY_INDEX_NAME, stopwordsPath);
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        // No-op for POS.
        // SynonymManager is used for the token value associated with the POS tag,
        // which is handled by the parent class. POS tags themselves are not stored in SynonymManager.
        if (type != AnnotationType.POS) {
            throw new IllegalArgumentException("BigramPosStitchGenerator's populateSpecificAnnotationSynonyms called with incorrect type: " + type);
        }
        logger.debug("populateSpecificAnnotationSynonyms is a no-op for BigramPosStitchGenerator.");
    }

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<AnnotationData> annotations = new ArrayList<>();
        String sql = String.format("""
            SELECT sentence_id, begin_char, end_char, token, pos
            FROM annotations
            WHERE document_id = ?
                AND pos NOT IN %s
            ORDER BY sentence_id, begin_char
        """, POSIndexGenerator.POS_TAGS_TO_EXCLUDE_SQL);


        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String posTag = rs.getString("pos");
                    String token = rs.getString("token");

                    if (token == null || token.isEmpty() || posTag == null || posTag.isEmpty()) {
                        logger.trace("Skipping POS annotation due to null/empty token or posTag. Doc ID: {}, Token: '{}', POS: '{}'", documentId, token, posTag);
                        continue;
                    }

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
            logger.error("SQLException in fetchAnnotationsForDocument for Bigram POS stitch, doc ID {}: {}", documentId, e.getMessage(), e);
            throw e;
        }
        if (annotations.isEmpty()) {
            logger.trace("No POS annotations found or all filtered for document ID {} for {} index.", documentId, MY_INDEX_NAME);
        } else {
            logger.trace("Fetched {} POS annotations for document ID {} for {} index.", annotations.size(), documentId, MY_INDEX_NAME);
        }
        return annotations;
    }

    @Override
    protected boolean requiresSynonymIdForAnnotationValue() {
        return true; // The token associated with the POS tag needs its ID from SynonymManager
    }

    @Override
    protected AnnotationType getManagedAnnotationType() {
        return AnnotationType.POS;
    }

    @Override
    public String getIndexName() {
        return MY_INDEX_NAME;
    }
}