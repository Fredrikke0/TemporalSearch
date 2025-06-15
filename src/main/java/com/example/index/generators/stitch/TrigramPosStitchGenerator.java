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

public final class TrigramPosStitchGenerator extends AbstractNgramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(TrigramPosStitchGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_trigram_pos";

    public TrigramPosStitchGenerator(
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
              AnnotationType.POS);
        logger.info("TrigramPosStitchGenerator initialized for index type: {}. Stopwords path: '{}'", MY_INDEX_NAME, stopwordsPath);
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        if (type != AnnotationType.POS) {
            throw new IllegalArgumentException("TrigramPosStitchGenerator's populateSpecificAnnotationSynonyms called with incorrect type: " + type);
        }
        logger.debug("populateSpecificAnnotationSynonyms is a no-op for TrigramPosStitchGenerator.");
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
                        logger.trace("Skipping POS annotation due to null/empty token or posTag. Doc ID: {}, Token: '{}', POS: '{}'", documentId, token, posTag);
                        continue;
                    }

                    rawAnnotationsFromDb.add(new AnnotationData(
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        posTag.toUpperCase(),
                        token.toLowerCase()
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("SQLException in fetchAnnotationsForDocument for Trigram POS stitch, doc ID {}: {}", documentId, e.getMessage(), e);
            throw e;
        }

        // Filter the annotations before returning
        List<AnnotationData> filteredAnnotations = filterAnnotationsBySentenceCharacterSpan(
            rawAnnotationsFromDb, documentId, "Trigram POS");

        if (filteredAnnotations.isEmpty()) {
            if (!rawAnnotationsFromDb.isEmpty()) {
                logger.trace("No POS annotations remaining after span filtering for document ID {} for {} (Trigram) index.", documentId, MY_INDEX_NAME);
            } else {
                logger.trace("No POS annotations found for document ID {} for {} (Trigram) index.", documentId, MY_INDEX_NAME);
            }
        } else {
            if (rawAnnotationsFromDb.size() != filteredAnnotations.size()) {
                 logger.trace("Fetched {} raw POS annotations, filtered to {} for document ID {} for {} (Trigram) index.",
                         rawAnnotationsFromDb.size(), filteredAnnotations.size(), documentId, MY_INDEX_NAME);
            } else {
                 logger.trace("Fetched {} POS annotations (no filtering needed) for document ID {} for {} (Trigram) index.", filteredAnnotations.size(), documentId, MY_INDEX_NAME);
            }
        }
        return filteredAnnotations;
    }

    @Override
    protected boolean requiresSynonymIdForAnnotationValue() {
        return true; // Token associated with POS tag needs ID from SynonymManager
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