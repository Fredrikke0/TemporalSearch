package com.example.index.generators.stitch;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.index.AnnotationType;
import com.example.index.generators.NerIndexGenerator;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;

public final class UnigramNerStitchGenerator extends AbstractNgramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(UnigramNerStitchGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_unigram_ner";

    public UnigramNerStitchGenerator(
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

    private record RawAnnotation(int sentenceId, int beginChar, int endChar, String token, String nerTag) implements SentenceSpanFilterable {
        @Override
        public String getFilterLogDetail() {
            return "NER: " + nerTag + ", Token: " + token;
        }
    }

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<RawAnnotation> rawAnnotationsFromDb = new ArrayList<>();
        String sql = String.format("""
            SELECT sentence_id, begin_char, end_char, token, ner
            FROM annotations
            WHERE document_id = ?
                AND ner != '' AND ner IS NOT NULL
                AND ner NOT IN %s
                AND (pos IS NULL OR pos NOT IN ('FW', 'ADD'))
            ORDER BY sentence_id, begin_char
        """, NerIndexGenerator.NER_TAGS_TO_EXCLUDE_SQL);

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rawAnnotationsFromDb.add(new RawAnnotation(
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        rs.getString("token"),
                        rs.getString("ner")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("SQLException in fetchAnnotationsForDocument for Unigram NER stitch (raw fetch), doc ID {}: {}", documentId, e.getMessage(), e);
            throw e;
        }


        List<AnnotationData> groupedAnnotations = new ArrayList<>();
        if (rawAnnotationsFromDb.isEmpty()) {
            logger.trace("No raw NER annotations found for document ID {} for {} index. (Span filtering is now done in Annotations.java)", documentId, MY_INDEX_NAME);
            return groupedAnnotations;
        }

        List<String> currentEntityRawTokens = new ArrayList<>();
        String currentEntityType = null;
        int currentEntitySentId = -1;
        int currentEntityBeginChar = -1;
        int previousTokenEndChar = -1;

        for (int i = 0; i < rawAnnotationsFromDb.size(); i++) {
            RawAnnotation currentAnnotation = rawAnnotationsFromDb.get(i);
            boolean entityBreak = false;

            if (currentEntityType != null) {
                if (!currentAnnotation.nerTag().equals(currentEntityType) ||
                    currentAnnotation.sentenceId() != currentEntitySentId ||
                    currentAnnotation.beginChar() > previousTokenEndChar + 2) { // Allow up to one separator char (space/punct)
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
                        Objects.requireNonNull(currentEntityType).toUpperCase(),
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
            previousTokenEndChar = currentAnnotation.endChar();

            if (i == rawAnnotationsFromDb.size() - 1) {
                if (!currentEntityRawTokens.isEmpty() && currentEntityType != null) {
                    String entityValue = String.join(" ", currentEntityRawTokens).toLowerCase();
                    groupedAnnotations.add(new AnnotationData(
                        currentEntitySentId,
                        currentEntityBeginChar,
                        previousTokenEndChar,
                        Objects.requireNonNull(currentEntityType).toUpperCase(),
                        entityValue
                    ));
                }
            }
        }

        if (groupedAnnotations.isEmpty()) {
            logger.trace("No grouped NER annotations after processing for document ID {} for {} index.", documentId, MY_INDEX_NAME);
        } else {
            logger.trace("Fetched {} grouped NER annotations for document ID {} for {} index.", groupedAnnotations.size(), documentId, MY_INDEX_NAME);
        }
        return groupedAnnotations;
    }

    @Override
    protected boolean requiresSynonymIdForAnnotationValue() {
        return true;
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