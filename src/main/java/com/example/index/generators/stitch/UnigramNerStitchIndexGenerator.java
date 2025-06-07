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
import com.example.index.generators.NerIndexGenerator;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;

public final class UnigramNerStitchIndexGenerator extends AbstractNgramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(UnigramNerStitchIndexGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_unigram_ner";

    public UnigramNerStitchIndexGenerator(
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
              AnnotationType.NER);
        logger.info("UnigramNerStitchIndexGenerator initialized for index type: {}. Stopwords path: '{}'", MY_INDEX_NAME, stopwordsPath);
        logger.debug("Using NER exclusion list from NerIndexGenerator: ner NOT IN {}", NerIndexGenerator.NER_TAGS_TO_EXCLUDE_SQL);
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        if (type != AnnotationType.NER) {
            throw new IllegalArgumentException("UnigramNerStitchIndexGenerator's populateSpecificAnnotationSynonyms called with incorrect type: " + type);
        }
        logger.debug("populateSpecificAnnotationSynonyms is a no-op for UnigramNerStitchIndexGenerator as NER tags are not stored in SynonymManager and normalized_ner IDs are handled by the parent.");
    }

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<AnnotationData> annotations = new ArrayList<>();
        // Fetch NER types and their corresponding normalized_ner values for the given document.
        String sql = String.format("""
            SELECT sentence_id, begin_char, end_char, ner, normalized_ner
            FROM annotations
            WHERE document_id = ?
                AND normalized_ner IS NOT NULL AND normalized_ner != ''
                AND ner NOT IN %s
            ORDER BY sentence_id, begin_char
        """, NerIndexGenerator.NER_TAGS_TO_EXCLUDE_SQL);

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String nerTag = rs.getString("ner");
                    String normalizedNer = rs.getString("normalized_ner");

                    if (normalizedNer == null || normalizedNer.isEmpty() || nerTag == null || nerTag.isEmpty()) {
                        logger.trace("Skipping NER annotation due to null/empty normalized_ner or nerTag. Doc ID: {}, NER: '{}', Normalized: '{}'", documentId, nerTag, normalizedNer);
                        continue;
                    }

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
    protected boolean requiresSynonymIdForAnnotationValue() {
        return true; // Default from AbstractUnigramStitchGenerator, NER values use synonym ID
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