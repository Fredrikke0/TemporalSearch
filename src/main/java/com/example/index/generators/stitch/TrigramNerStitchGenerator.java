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

public final class TrigramNerStitchGenerator extends AbstractNgramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(TrigramNerStitchGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_trigram_ner";

    public TrigramNerStitchGenerator(
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
              AnnotationType.NER);
        logger.info("TrigramNerStitchGenerator initialized for index type: {}. Stopwords path: '{}'", MY_INDEX_NAME, stopwordsPath);
        logger.debug("Using NER exclusion list from NerIndexGenerator: ner NOT IN {}", NerIndexGenerator.NER_TAGS_TO_EXCLUDE_SQL);
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        if (type != AnnotationType.NER) {
            throw new IllegalArgumentException("TrigramNerStitchGenerator's populateSpecificAnnotationSynonyms called with incorrect type: " + type);
        }
        logger.debug("populateSpecificAnnotationSynonyms is a no-op for TrigramNerStitchGenerator.");
    }

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<AnnotationData> annotations = new ArrayList<>();
        String sql = String.format("""
            SELECT sentence_id, begin_char, end_char, ner, normalized_ner
            FROM annotations
            WHERE document_id = ?
                AND ner != ''
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
                        logger.trace("Skipping NER annotation due to null/empty. Doc ID: {}, NER: '{}', Normalized: '{}'", documentId, nerTag, normalizedNer);
                        continue;
                    }

                    annotations.add(new AnnotationData(
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        nerTag.toUpperCase(),
                        normalizedNer.toLowerCase()
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("SQLException in fetchAnnotationsForDocument for Trigram NER stitch, doc ID {}: {}", documentId, e.getMessage(), e);
            throw e;
        }
        return annotations;
    }

    @Override
    protected boolean requiresSynonymIdForAnnotationValue() {
        return true; // Normalized NER string needs ID
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