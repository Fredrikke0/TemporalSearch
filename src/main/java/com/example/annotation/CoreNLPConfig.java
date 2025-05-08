package com.example.annotation;

import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Properties;

/**
 * Configuration class for CoreNLP pipeline with optimized settings.
 * This class encapsulates all CoreNLP-related configuration and provides
 * factory methods for creating optimized pipeline instances.
 */
public class CoreNLPConfig {
    private static final Logger logger = LoggerFactory.getLogger(CoreNLPConfig.class);
    
    // Default thread count if not specified
    private static final int DEFAULT_THREADS = Runtime.getRuntime().availableProcessors();
    
    // Maximum lengths for different components to prevent OOM and speed up processing
    private static final int MAX_SENTENCE_LENGTH = 120;
    
    // Path to SR parser model. Only used if USE_PARSE_ANNOTATOR is true.
    private static final String SR_PARSER_MODEL = "stanford-english-extra-corenlp-models-current/edu/stanford/nlp/models/srparser/englishSR.ser.gz";
    
    // Whether to use parse annotator instead of depparse
    private static final boolean USE_PARSE_ANNOTATOR = false;
    
    private final Properties properties;
    
    /**
     * Creates a new CoreNLPConfig with optimized settings
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
     * Creates and returns a new StanfordCoreNLP pipeline instance with the optimized configuration
     * @return A configured StanfordCoreNLP pipeline instance
     */
    public StanfordCoreNLP createPipeline() {
        logger.debug("Creating new CoreNLP pipeline with optimized configuration");
        return new StanfordCoreNLP(properties);
    }
    
    /**
     * Creates optimized properties for the CoreNLP pipeline
     * @param threads Number of threads to use
     * @return Properties configured for optimal performance
     */
    private static Properties createOptimizedProperties(int threads) {
        Properties props = new Properties();
        
        // Core annotators - only what we actually use in Annotations.java
        String annotators = USE_PARSE_ANNOTATOR ? 
            "tokenize,ssplit,pos,lemma,ner,parse" : 
            "tokenize,ssplit,pos,lemma,ner,depparse";
        props.setProperty("annotators", annotators);
        props.setProperty("threads", String.valueOf(threads));
        
        // Check if SR parser model exists and use it if available
        if (USE_PARSE_ANNOTATOR) {
            java.nio.file.Path modelPath = java.nio.file.Paths.get(SR_PARSER_MODEL);
            if (java.nio.file.Files.exists(modelPath)) {
                logger.info("Using SR parser model from local path: {}", modelPath.toAbsolutePath());
                props.setProperty("parse.model", modelPath.toAbsolutePath().toString());
            }
        }
        
        // Parser specific settings
        props.setProperty("parse.maxlen", String.valueOf(MAX_SENTENCE_LENGTH));
        // props.setProperty("parse.binaryTrees", "false");
        props.setProperty("parse.buildgraphs", "true");
        props.setProperty("parse.keepPunct", "false");  // Don't create nodes for punctuation
        props.setProperty("parse.nthreads", String.valueOf(threads));
        
        // Ner settings - Commented out settings are default settings
        // props.setProperty("ner.useSUTime", "true");
        // props.setProperty("ner.applyNumericClassifiers", "true");
        // props.setProperty("ner.applyFineGrained", "true");        // Disable for speed
        // props.setProperty("ner.useNGrams", "true");               // What is this?
        // props.setProperty("ner.buildEntityMentions", "true");
        
         
        // Length constraints - balanced for speed
        props.setProperty("pos.maxlen", String.valueOf(MAX_SENTENCE_LENGTH));

        // Tokenizer settings
        props.setProperty("tokenize.options", String.join(",",
            //"normalizeParentheses=true",
            //"normalizeOtherBrackets=true",
            "ptb3Escaping=false"
        ));
        
        // Sentence splitting
        // props.setProperty("ssplit.boundaryTokenRegex", "\\.|[!?]+");  // Default boundary regex
        props.setProperty("ssplit.newlineIsSentenceBreak", "two"); // Needs testing
        props.setProperty("tokenize.tokenizeNLs", "false");       // Handle newlines properly
        

        
        return props;
    }
    
    /**
     * Gets the underlying properties
     * @return The CoreNLP properties
     */
    public Properties getProperties() {
        return new Properties(properties);
    }
} 