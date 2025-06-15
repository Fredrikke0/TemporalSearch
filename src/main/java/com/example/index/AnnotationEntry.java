package com.example.index;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import com.example.index.generators.IndexGenerator;

/**
 * Represents an annotation entry from the SQLite database.
 * Contains information about a token and its linguistic features.
 */
public final class AnnotationEntry implements IndexEntry {
    private final long annotationId;
    private final int documentId;
    private final int sentenceId;
    private final int beginChar;
    private final int endChar;
    private final String token;
    private final String pos;
    private final String ner;            // Added NER type
    private final String normalizedNer;  // Added normalized NER value
    private final String lemma;          // Added lemma

    public AnnotationEntry(long annotationId, int documentId, int sentenceId, int beginChar, int endChar,
            String token, String pos, String ner, String normalizedNer, String lemma) {
        this.annotationId = annotationId;
        this.documentId = documentId;
        this.sentenceId = sentenceId;
        this.beginChar = beginChar;
        this.endChar = endChar;
        this.token = token;
        this.pos = pos;
        this.ner = ner;
        this.normalizedNer = normalizedNer;
        this.lemma = lemma;
    }

    /**
     * @return The unique identifier for this annotation
     */
    public long getAnnotationId() {
        return annotationId;
    }

    @Override
    public int getDocumentId() {
        return documentId;
    }

    @Override
    public int getSentenceId() {
        return sentenceId;
    }

    @Override
    public int getBeginChar() {
        return beginChar;
    }

    @Override
    public int getEndChar() {
        return endChar;
    }

    /**
     * @return The original token text
     */
    public String getToken() {
        return token;
    }

    /**
     * @return The part-of-speech tag for the token
     */
    public String getPos() {
        return pos;
    }

    // New getters
    public String getNer() {
        return ner;
    }

    public String getNormalizedNer() {
        return normalizedNer;
    }

    public String getLemma() {
        return lemma;
    }

    /**
     * Filters a list of AnnotationEntry objects for a single sentence based on the
     * MAX_SENTENCE_CHAR_SPAN_FROM_FIRST_TOKEN limit.
     * Assumes the input list {@code sentenceTokens} is already sorted by beginChar.
     *
     * @param sentenceTokens List of tokens for a single sentence, sorted by beginChar.
     * @param documentId Document ID for logging.
     * @param sentenceIdForLog Sentence ID for logging.
     * @param generatorNameForLog Name of the calling generator for logging clarity.
     * @param logger Logger instance from the calling generator.
     * @return A new list containing the filtered tokens.
     */
    public static List<AnnotationEntry> filterTokensBySentenceSpan(
            List<AnnotationEntry> sentenceTokens,
            int documentId,
            int sentenceIdForLog,
            String generatorNameForLog,
            Logger logger) {

        if (sentenceTokens == null || sentenceTokens.isEmpty()) {
            return new ArrayList<>(); // Or return sentenceTokens itself if preferred for null input
        }

        List<AnnotationEntry> filteredList = new ArrayList<>();
        AnnotationEntry firstToken = sentenceTokens.get(0);
        int firstTokenBeginChar = firstToken.getBeginChar();
        boolean truncationLogged = false;

        for (AnnotationEntry entry : sentenceTokens) {
            if (entry.getBeginChar() <= firstTokenBeginChar + IndexGenerator.MAX_SENTENCE_CHAR_SPAN_FROM_FIRST_TOKEN) {
                filteredList.add(entry);
            } else {
                if (!truncationLogged && logger != null) {
                    logger.trace("Sentence (doc_id: {}, sentence_id: {}) token processing truncated for {}. Token with begin_char {} (text: '{}') exceeded limit (first_token_begin_char {} + span {}). Last token included had begin_char: {}.",
                            documentId,
                            sentenceIdForLog,
                            generatorNameForLog,
                            entry.getBeginChar(),
                            entry.getToken(),
                            firstTokenBeginChar,
                            IndexGenerator.MAX_SENTENCE_CHAR_SPAN_FROM_FIRST_TOKEN,
                            (filteredList.isEmpty() ? "N/A" : filteredList.get(filteredList.size()-1).getBeginChar()));
                    truncationLogged = true;
                }
                // Since the input list is sorted by beginChar, once we exceed the limit,
                // all subsequent tokens in this sentence will also exceed it.
                break;
            }
        }
        return filteredList;
    }
}