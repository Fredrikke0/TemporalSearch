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
import com.example.index.generators.POSIndexGenerator;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;

public final class UnigramPosStitchGenerator extends AbstractNgramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(UnigramPosStitchGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_unigram_pos";

    public UnigramPosStitchGenerator(
            IndexAccessInterface indexAccess,
            String stopwordsPath,
            Connection sqliteConn,
            ProgressTracker progress,
            int batchSize,
            Path customTempPath,
            SynonymManager sharedSynonymManager) throws IOException {
        super(1, // N=1 for unigram
              indexAccess,
              stopwordsPath,
              sqliteConn,
              progress,
              batchSize,
              customTempPath,
              sharedSynonymManager,
              AnnotationType.POS);
        logger.info("UnigramPosStitchIndexGenerator initialized for index type: {}. Stopwords path: '{}'", MY_INDEX_NAME, stopwordsPath);
        logger.debug("Using POS exclusion list from POSIndexGenerator: pos NOT IN {}", POSIndexGenerator.POS_TAGS_TO_EXCLUDE_SQL);
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        if (type != AnnotationType.POS) {
            throw new IllegalArgumentException("UnigramPosStitchIndexGenerator's populateSpecificAnnotationSynonyms called with incorrect type: " + type);
        }
        logger.debug("populateSpecificAnnotationSynonyms for UnigramPosStitchIndexGenerator relies on parent to handle token synonym IDs.");
    }

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<AnnotationData> rawAnnotationsFromDb = new ArrayList<>();
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
                        logger.trace("Skipping POS annotation due to null/empty token or posTag. Doc ID: {}, POS: '{}', Token: '{}'", documentId, posTag, token);
                        continue;
                    }

                    rawAnnotationsFromDb.add(new AnnotationData(
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        posTag.toUpperCase(),   // annotationKeyComponent (e.g., "NN")
                        token.toLowerCase()     // specificValueForSynonym (e.g., "cat")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("SQLException in fetchAnnotationsForDocument for POS stitch, doc ID {}: {}", documentId, e.getMessage(), e);
            throw e;
        }

        if (rawAnnotationsFromDb.isEmpty()) {
            logger.trace("No raw POS annotations found for document ID {} for {} (Unigram) index.", documentId, MY_INDEX_NAME);
        }
        if (!rawAnnotationsFromDb.isEmpty()) {
            logger.trace("Fetched {} raw POS annotations for document ID {} for {} (Unigram) index. (Span filtering is now done in Annotations.java)",
                     rawAnnotationsFromDb.size(), documentId, MY_INDEX_NAME);
        }
        return rawAnnotationsFromDb;
    }

    @Override
    protected boolean requiresSynonymIdForAnnotationValue() {
        return true;
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
