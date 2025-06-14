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

    // Temporary internal record to hold raw annotation data including the token
    private record RawAnnotation(int sentenceId, int beginChar, int endChar, String token, String nerTag, int originalAnnotationEndChar) {}

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<RawAnnotation> rawAnnotations = new ArrayList<>();
        // Modified SQL to fetch 'token' and ensure ner is not null
        String sql = String.format("""
            SELECT sentence_id, begin_char, end_char, token, ner
            FROM annotations
            WHERE document_id = ?
                AND ner != '' AND ner IS NOT NULL
                AND ner NOT IN %s
            ORDER BY sentence_id, begin_char
        """, NerIndexGenerator.NER_TAGS_TO_EXCLUDE_SQL);

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rawAnnotations.add(new RawAnnotation(
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"), // This is the end_char of the individual token
                        rs.getString("token"),
                        rs.getString("ner"),
                        rs.getInt("end_char") // Store original end_char for adjacency check
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("SQLException in fetchAnnotationsForDocument for Trigram NER stitch (raw fetch), doc ID {}: {}", documentId, e.getMessage(), e);
            throw e;
        }

        List<AnnotationData> groupedAnnotations = new ArrayList<>();
        if (rawAnnotations.isEmpty()) {
            return groupedAnnotations;
        }

        List<String> currentEntityRawTokens = new ArrayList<>();
        String currentEntityType = null;
        int currentEntitySentId = -1;
        int currentEntityBeginChar = -1;
        int previousTokenEndChar = -1;

        for (int i = 0; i < rawAnnotations.size(); i++) {
            RawAnnotation currentAnnotation = rawAnnotations.get(i);
            boolean entityBreak = false;

            if (currentEntityType != null) {
                if (!currentAnnotation.nerTag().equals(currentEntityType) ||
                    currentAnnotation.sentenceId() != currentEntitySentId ||
                    currentAnnotation.beginChar() > previousTokenEndChar + 1) {
                    entityBreak = true;
                }
            }

            if (entityBreak) {
                if (!currentEntityRawTokens.isEmpty()) {
                    String entityValue = String.join(" ", currentEntityRawTokens).toLowerCase();
                    groupedAnnotations.add(new AnnotationData(
                        currentEntitySentId,
                        currentEntityBeginChar,
                        previousTokenEndChar,
                        currentEntityType.toUpperCase(),
                        entityValue
                    ));
                }
                currentEntityRawTokens.clear();
                currentEntityType = null;
            }

            if (currentEntityType == null) {
                currentEntityType = currentAnnotation.nerTag();
                currentEntitySentId = currentAnnotation.sentenceId();
                currentEntityBeginChar = currentAnnotation.beginChar();
            }
            currentEntityRawTokens.add(currentAnnotation.token());
            previousTokenEndChar = currentAnnotation.originalAnnotationEndChar();

            if (i == rawAnnotations.size() - 1) {
                if (!currentEntityRawTokens.isEmpty() && currentEntityType != null) {
                    String entityValue = String.join(" ", currentEntityRawTokens).toLowerCase();
                    groupedAnnotations.add(new AnnotationData(
                        currentEntitySentId,
                        currentEntityBeginChar,
                        previousTokenEndChar,
                        currentEntityType.toUpperCase(),
                        entityValue
                    ));
                }
            }
        }
        return groupedAnnotations;
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