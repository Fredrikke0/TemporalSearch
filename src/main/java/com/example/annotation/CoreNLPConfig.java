package com.example.annotation;

import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stanford.nlp.pipeline.StanfordCoreNLP;

/**
 * Configuration class for CoreNLP pipeline with optimized settings.
 */
public class CoreNLPConfig {
    private static final Logger logger = LoggerFactory.getLogger(CoreNLPConfig.class);

    // Default thread count if not specified
    private static final int DEFAULT_THREADS = Runtime.getRuntime().availableProcessors();

    // Maximum sentence length (in characters) used for post-hoc truncation.
    // A sentence's kept tokens must all fit within this many characters from the
    // first token's begin position.
    public static final int MAX_SENTENCE_LENGTH = 150;

    // CoreNLP maxlen properties use TOKENS, not characters.
    // 150 characters roughly equals 25-30 English tokens (avg ~5 chars/word + space).
    // We set this slightly above the character equivalent so that CoreNLP skips
    // sentences that are so long they'd be heavily truncated anyway, saving time.
    // Sentences at or below this token count still get full annotation, then the
    // post-hoc character-based truncation keeps the first MAX_SENTENCE_LENGTH chars.
    private static final int MAX_SENTENCE_TOKENS = 30;

    // Whether to enable dependency parsing (depparse annotator).
    // When disabled, the pipeline skips depparse entirely, which speeds up
    // annotation and reduces database size. Downstream systems (indexing,
    // querying) auto-detect the absence of dependency data and handle it
    // gracefully.
    public static final boolean DEPENDENCY_ENABLED = false;

    private final Properties properties;

    /**
     * Creates a new CoreNLPConfig with optimized settings
     *
     * @param threads Number of threads to use for parallel processing
     */
    public CoreNLPConfig(int threads) {
        this.properties = createOptimizedProperties(threads);
        logger.debug("Initialized CoreNLP configuration with {} threads", threads);
    }

    /**
     * Creates a new CoreNLPConfig with default thread count
     */
    public CoreNLPConfig() {
        this(DEFAULT_THREADS);
    }

    /**
     * Creates and returns a new StanfordCoreNLP pipeline instance with the
     * optimized configuration
     *
     * @return A configured StanfordCoreNLP pipeline instance
     */
    public StanfordCoreNLP createPipeline() {
        logger.debug("Creating new CoreNLP pipeline with optimized configuration");
        return new StanfordCoreNLP(properties);
    }

    /**
     * Creates optimized properties for the CoreNLP pipeline
     *
     * @param threads Number of threads to use
     * @return Properties configured for optimal performance
     */
    private static Properties createOptimizedProperties(int threads) {
        Properties props = new Properties();

        // Core annotators - only what we actually use in Annotations.java
        String annotators = DEPENDENCY_ENABLED ? "tokenize,ssplit,pos,lemma,ner,depparse"
                : "tokenize,ssplit,pos,lemma,ner";
        props.setProperty("annotators", annotators);
        props.setProperty("threads", String.valueOf(threads));

        if (DEPENDENCY_ENABLED) {
            // Depparse parser specific settings
            props.setProperty("parse.maxlen", String.valueOf(MAX_SENTENCE_LENGTH));
            props.setProperty("parse.buildgraphs", "true");
            props.setProperty("parse.keepPunct", "false"); // Don't create nodes for punctuation
            props.setProperty("parse.nthreads", String.valueOf(threads));
        }

        // NER settings - Commented out settings are default settings
        // props.setProperty("ner.useSUTime", "true");
        // props.setProperty("ner.applyNumericClassifiers", "true");
        // props.setProperty("ner.applyFineGrained", "true"); // Disable for speed
        // props.setProperty("ner.useNGrams", "true"); // What is this?
        // props.setProperty("ner.buildEntityMentions", "true");

        // Length constraints - balanced for speed
        // maxlen is measured in TOKENS; MAX_SENTENCE_TOKENS (~30) ≈ 150 chars
        props.setProperty("pos.maxlen", String.valueOf(MAX_SENTENCE_TOKENS));
        props.setProperty("ner.maxlen", String.valueOf(MAX_SENTENCE_TOKENS));

        // Tokenizer settings
        props.setProperty("tokenize.options", String.join(",",
                // "normalizeParentheses=true",
                // "normalizeOtherBrackets=true",
                "ptb3Escaping=false",
                "untokenizable=noneKeep",
                "tokenizeNLs=false"));

        // Sentence splitting
        // props.setProperty("ssplit.boundaryTokenRegex", "\\.|[!?]+"); // Default
        // boundary regex
        props.setProperty("ssplit.newlineIsSentenceBreak", "two"); // Needs testing

        return props;
    }

    /**
     * Gets the underlying properties
     *
     * @return The CoreNLP properties
     */
    public Properties getProperties() {
        return new Properties(properties);
    }
}
