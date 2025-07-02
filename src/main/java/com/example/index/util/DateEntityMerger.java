package com.example.index.util;

import com.example.index.AnnotationEntry;
import com.example.index.generators.NerDateIndexGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class DateEntityMerger {
    private static final Logger logger = LoggerFactory.getLogger(DateEntityMerger.class);

    private DateEntityMerger() {
        // Prevent instantiation
    }

    /**
     * Merges a sorted list of AnnotationEntry objects into consolidated MergedDateEntity objects.
     * Assumes the input list is sorted by document_id, sentence_id, and begin_char.
     *
     * @param sortedAnnotations A list of AnnotationEntry, expected to be DATE annotations.
     * @return A list of merged date entities.
     */
    public static List<MergedDateEntity> merge(List<AnnotationEntry> sortedAnnotations) {
        List<MergedDateEntity> mergedEntities = new ArrayList<>();
        if (sortedAnnotations == null || sortedAnnotations.isEmpty()) {
            return mergedEntities;
        }

        List<AnnotationEntry> currentMergedEntityTokens = new ArrayList<>();

        for (AnnotationEntry currentEntry : sortedAnnotations) {
            String rawNormalizedDate = currentEntry.getNormalizedNer();

            if (rawNormalizedDate == null || rawNormalizedDate.isEmpty()) {
                processAndClear(mergedEntities, currentMergedEntityTokens);
                continue;
            }

            String normalizedDateKey = NerDateIndexGenerator.normalizeDateToKeyFormat(rawNormalizedDate);
            if (normalizedDateKey == null) {
                processAndClear(mergedEntities, currentMergedEntityTokens);
                continue;
            }

            if (currentMergedEntityTokens.isEmpty()) {
                currentMergedEntityTokens.add(currentEntry);
            } else {
                AnnotationEntry prevEntry = currentMergedEntityTokens.get(currentMergedEntityTokens.size() - 1);
                String prevNormalizedDateKey = NerDateIndexGenerator.normalizeDateToKeyFormat(prevEntry.getNormalizedNer());

                // Check for entity break
                boolean isSameEntity = normalizedDateKey.equals(prevNormalizedDateKey) &&
                    currentEntry.getDocumentId() == prevEntry.getDocumentId() &&
                    currentEntry.getSentenceId() == prevEntry.getSentenceId() &&
                    currentEntry.getBeginChar() <= prevEntry.getEndChar() + 2; // Allow small gap

                if (isSameEntity) {
                    currentMergedEntityTokens.add(currentEntry);
                } else {
                    processAndClear(mergedEntities, currentMergedEntityTokens);
                    currentMergedEntityTokens.add(currentEntry);
                }
            }
        }
        processAndClear(mergedEntities, currentMergedEntityTokens);

        return mergedEntities;
    }

    private static void processAndClear(List<MergedDateEntity> mergedEntities, List<AnnotationEntry> tokensToMerge) {
        if (tokensToMerge.isEmpty()) {
            return;
        }

        AnnotationEntry firstToken = tokensToMerge.get(0);
        AnnotationEntry lastToken = tokensToMerge.get(tokensToMerge.size() - 1);

        String rawNormalizedDate = firstToken.getNormalizedNer(); // All tokens have the same date
        String normalizedDateKey = NerDateIndexGenerator.normalizeDateToKeyFormat(rawNormalizedDate);

        if (normalizedDateKey == null) {
             logger.warn("Skipping entity post-merge due to normalization failure for date '{}' at doc/sent/char: {}/{}/{}",
                rawNormalizedDate, firstToken.getDocumentId(), firstToken.getSentenceId(), firstToken.getBeginChar());
            tokensToMerge.clear();
            return;
        }

        mergedEntities.add(new MergedDateEntity(
            firstToken.getDocumentId(),
            firstToken.getSentenceId(),
            firstToken.getBeginChar(),
            lastToken.getEndChar(),
            rawNormalizedDate
        ));

        tokensToMerge.clear();
    }

    /**
     * Represents a consolidated date entity after merging adjacent tokens.
     * @param documentId The document ID.
     * @param sentenceId The sentence ID.
     * @param beginChar The starting character offset of the merged entity.
     * @param endChar The ending character offset of the merged entity.
     * @param normalizedDate The raw normalized date string (e.g., "2023-01-15").
     */
    public record MergedDateEntity(int documentId, int sentenceId, int beginChar, int endChar, String normalizedDate) {}
}