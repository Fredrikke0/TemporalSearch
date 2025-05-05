package com.example.annotation;

import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.semgraph.*;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.*;
import java.util.*;
import me.tongfei.progressbar.*;
import java.nio.file.Path;
import java.io.PrintStream;
import java.io.OutputStream;
import java.time.temporal.ChronoUnit;

public class Annotations {
    private static final Logger logger = LoggerFactory.getLogger(Annotations.class);
    private final Path dbFile;
    private final Integer limit;
    private final StanfordCoreNLP pipeline;
    
    public Annotations(Path dbFile, int threads, Integer limit) {
        this.dbFile = dbFile;
        this.limit = limit;
        
        // Create optimized CoreNLP configuration
        this.pipeline = createCoreNLPPipeline(threads);
        logger.debug("Created CoreNLP pipeline with optimized configuration");
    }

    private static StanfordCoreNLP createCoreNLPPipeline(int threads) {
        CoreNLPConfig config = new CoreNLPConfig(threads);
        return config.createPipeline();
    }

    public void processDocuments() throws Exception {
        String url = "jdbc:sqlite:" + dbFile;
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(url);
            conn.setAutoCommit(false);
            
            createTables(conn, false);
            
            // Build base query conditions (handling length limit)
            List<String> conditions = new ArrayList<>();
            conditions.add("LENGTH(text) <= 15000"); // Always filter by length

            // Build count query for documents that will actually be processed
            StringBuilder countQueryBuilder = new StringBuilder("SELECT COUNT(*) FROM documents");
            if (!conditions.isEmpty()) { // Apply conditions (length filter)
                countQueryBuilder.append(" WHERE ").append(String.join(" AND ", conditions));
            }
            if (limit != null) {
                countQueryBuilder.append(" LIMIT ").append(limit); // Apply limit directly to count query
            }

            String countQuery = countQueryBuilder.toString();
            
            int documentsToProcess = 0;
            try (Statement countStmt = conn.createStatement();
                 ResultSet countRs = countStmt.executeQuery(countQuery)) {
                if (countRs.next()) {
                    documentsToProcess = countRs.getInt(1);
                }
            }
            
            logger.info("Found {} documents matching criteria (length <= 15000, limit={}) to process.",
                        documentsToProcess, limit != null ? limit : "none");
            
            // Build the main processing query
            String query = buildQuery(limit, true); // Pass flag to include length filter
            
            final int commitBatchSize = 100;
            int documentsInBatch = 0;
            int totalProcessed = 0;
            
            logger.debug("Executing query to fetch documents...");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                
                 // Check system property to potentially disable progress bar output
                 boolean silentProgress = Boolean.parseBoolean(System.getProperty("progbar.silent", "false"));
                 
                 ProgressBarBuilder pbb = new ProgressBarBuilder()
                    .setTaskName("Processing documents")
                    .setInitialMax(documentsToProcess); // Use actual count to process
                    
                // If silent, set a consumer that does nothing
                if (silentProgress) {
                    logger.debug("Progress bar output disabled via system property.");
                    // Provide a no-op ProgressBarConsumer
                    pbb.setConsumer(new ProgressBarConsumer() {
                        @Override public void accept(String M) { /* No-op */ }
                        @Override public int getMaxRenderedLength() { return 0; }
                        @Override public void close() { /* No-op */ }
                    });
                }
                
                // Build the ProgressBar within the try-with-resources
                try (ProgressBar pb = pbb.build()) {
                    logger.debug("Starting document processing loop...");
                    while (rs.next()) {
                        int documentId = rs.getInt("document_id");
                        String text = rs.getString("text");
                        
                        AnnotationResult result = processTextWithCoreNLP(pipeline, text, documentId);
                        insertData(conn, result.annotations, result.dependencies);
                        
                        pb.step();
                        totalProcessed++;
                        documentsInBatch++;
                        
                        if (documentsInBatch >= commitBatchSize) {
                            conn.commit();
                            logger.debug("Committed batch of {} documents", documentsInBatch);
                            documentsInBatch = 0;
                        }
                        
                        if (totalProcessed % 10 == 0) {
                            pb.setExtraMessage(String.format("(%d/%d)", totalProcessed, documentsToProcess));
                        }
                    }
                    
                    if (documentsInBatch > 0) {
                        conn.commit();
                        logger.debug("Committed final batch of {} documents", documentsInBatch);
                    }
                } // ProgressBar pb is closed here

            } catch (SQLException e) {
                logger.error("SQL Error during document processing, attempting rollback.", e);
                if (conn != null) {
                    try {
                        conn.rollback();
                        logger.info("Transaction rolled back successfully.");
                    } catch (SQLException ex) {
                        logger.error("Error attempting to rollback transaction.", ex);
                    }
                }
                throw e;
            } finally {
                if (conn != null) {
                    try {
                       conn.close();
                    } catch (SQLException e) {
                       logger.error("Error closing database connection.", e);
                    }
                }
            }

        } catch (SQLException e) {
            logger.error("Failed to connect to or process database: {}", url, e);
            throw e;
        } finally {
            if (conn != null && !conn.isClosed()) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.error("Error closing database connection in final finally block.", e);
                }
            }
        }
    }

    private static class AnnotationResult {
        final List<Map<String, Object>> annotations;
        final List<Map<String, Object>> dependencies;

        AnnotationResult(List<Map<String, Object>> annotations, List<Map<String, Object>> dependencies) {
            this.annotations = annotations;
            this.dependencies = dependencies;
        }
    }

    private static void createTables(Connection conn, boolean overwrite) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if (overwrite) {
                stmt.execute("DROP TABLE IF EXISTS annotations");
                stmt.execute("DROP TABLE IF EXISTS dependencies");
            }
            stmt.execute("DROP TABLE IF EXISTS index_table");
            stmt.execute("PRAGMA foreign_keys = ON");

            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS annotations (
                            annotation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                            document_id INTEGER NOT NULL,
                            sentence_id INTEGER,
                            begin_char INTEGER,
                            end_char INTEGER,
                            token TEXT,
                            lemma TEXT,
                            pos TEXT,
                            ner TEXT,
                            normalized_ner TEXT,
                            FOREIGN KEY (document_id) REFERENCES documents(document_id)
                        )
                    """);

            // Add the index for faster lookups when not overwriting
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_annotations_document_id ON annotations (document_id)");

            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS dependencies (
                            dependency_id INTEGER PRIMARY KEY AUTOINCREMENT,
                            document_id INTEGER NOT NULL,
                            sentence_id INTEGER,
                            begin_char INTEGER,
                            end_char INTEGER,
                            head_token TEXT,
                            dependent_token TEXT,
                            relation TEXT,
                            FOREIGN KEY (document_id) REFERENCES documents(document_id)
                        )
                    """);
        }
    }

    /**
     * Processes text with CoreNLP pipeline, chunking if needed, and returns annotations.
     * 
     * @param pipeline The CoreNLP pipeline to use for processing
     * @param text The text to process
     * @param documentId The ID of the document being processed
     * @return The AnnotationResult containing annotations and dependencies
     */
    private static AnnotationResult processTextWithCoreNLP(StanfordCoreNLP pipeline, String text, int documentId) {
        List<Map<String, Object>> annotations = new ArrayList<>();
        List<Map<String, Object>> dependencies = new ArrayList<>();
        
        
        // Process the document directly without chunking
        CoreDocument document = new CoreDocument(text);
        pipeline.annotate(document);
        
        int sentenceId = 0;
        for (CoreSentence sentence : document.sentences()) {
            List<CoreLabel> tokens = sentence.tokens();
            
            // Process tokens
            for (CoreLabel token : tokens) {
                Map<String, Object> annotation = new HashMap<>();
                annotation.put("document_id", documentId);
                annotation.put("sentence_id", sentenceId);
                annotation.put("begin_char", token.beginPosition());
                annotation.put("end_char", token.endPosition());
                annotation.put("token", token.word());
                annotation.put("lemma", token.lemma());
                annotation.put("pos", token.tag());
                annotation.put("ner", token.ner());
                annotation.put("normalized_ner",
                        token.get(CoreAnnotations.NormalizedNamedEntityTagAnnotation.class));

                annotations.add(annotation);
            }

            // Process dependencies
            SemanticGraph dependencies_graph = sentence.dependencyParse();
            if (dependencies_graph != null) {
                for (SemanticGraphEdge edge : dependencies_graph.edgeIterable()) {
                    IndexedWord source = edge.getSource();
                    IndexedWord target = edge.getTarget();

                    int beginChar = Math.min(source.beginPosition(), target.beginPosition());
                    int endChar = Math.max(source.endPosition(), target.endPosition());

                    Map<String, Object> dependency = new HashMap<>();
                    dependency.put("document_id", documentId);
                    dependency.put("sentence_id", sentenceId);
                    dependency.put("begin_char", beginChar);
                    dependency.put("end_char", endChar);
                    dependency.put("head_token", source.word());
                    dependency.put("dependent_token", target.word());
                    dependency.put("relation", edge.getRelation().toString());

                    dependencies.add(dependency);
                }
            }
            sentenceId++;
        }
        
        return new AnnotationResult(annotations, dependencies);
    }

    private static void insertData(Connection conn, List<Map<String, Object>> annotations,
            List<Map<String, Object>> dependencies) throws SQLException {
        String annotationSQL = """
                    INSERT INTO annotations (
                        document_id, sentence_id, begin_char, end_char, token,
                        lemma, pos, ner, normalized_ner
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        String dependencySQL = """
                    INSERT INTO dependencies (
                        document_id, sentence_id, begin_char, end_char,
                        head_token, dependent_token, relation
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement annotationStmt = conn.prepareStatement(annotationSQL);
                PreparedStatement dependencyStmt = conn.prepareStatement(dependencySQL)) {

            // Use larger batch sizes for better performance
            annotationStmt.setFetchSize(10000);
            dependencyStmt.setFetchSize(10000);

            for (Map<String, Object> annotation : annotations) {
                annotationStmt.setInt(1, (Integer) annotation.get("document_id"));
                annotationStmt.setInt(2, (Integer) annotation.get("sentence_id"));
                annotationStmt.setInt(3, (Integer) annotation.get("begin_char"));
                annotationStmt.setInt(4, (Integer) annotation.get("end_char"));
                annotationStmt.setString(5, (String) annotation.get("token"));
                annotationStmt.setString(6, (String) annotation.get("lemma"));
                annotationStmt.setString(7, (String) annotation.get("pos"));
                annotationStmt.setString(8, (String) annotation.get("ner"));
                annotationStmt.setString(9, (String) annotation.get("normalized_ner"));

                annotationStmt.addBatch();
            }
            annotationStmt.executeBatch();

            for (Map<String, Object> dependency : dependencies) {
                dependencyStmt.setInt(1, (Integer) dependency.get("document_id"));
                dependencyStmt.setInt(2, (Integer) dependency.get("sentence_id"));
                dependencyStmt.setInt(3, (Integer) dependency.get("begin_char"));
                dependencyStmt.setInt(4, (Integer) dependency.get("end_char"));
                dependencyStmt.setString(5, (String) dependency.get("head_token"));
                dependencyStmt.setString(6, (String) dependency.get("dependent_token"));
                dependencyStmt.setString(7, (String) dependency.get("relation"));

                dependencyStmt.addBatch();
            }
            dependencyStmt.executeBatch();
        }
    }

    // Overload buildQuery to optionally include the length filter in the WHERE clause
    private static String buildQuery(Integer limit, boolean filterLength) {
        StringBuilder query = new StringBuilder("SELECT document_id, text FROM documents");
        List<String> conditions = new ArrayList<>();

        conditions.add("LENGTH(text) <= 15000");

        if (!conditions.isEmpty()) {
            query.append(" WHERE ").append(String.join(" AND ", conditions));
        }

        if (limit != null) {
            query.append(" LIMIT ").append(limit);
        }

        return query.toString();
    }

    /**
     * Returns annotation status for the given project DB path.
     * Checks for annotation columns/tables, finds max_annotated_id and max_doc_id.
     * Returns a status object: { needsProcessing: boolean, startDocumentId: int }
     */
    public static class AnnotationStatus {
        public final boolean needsProcessing;
        public final int startDocumentId;
        public AnnotationStatus(boolean needsProcessing, int startDocumentId) {
            this.needsProcessing = needsProcessing;
            this.startDocumentId = startDocumentId;
        }
    }

    public static AnnotationStatus getAnnotationStatus(Path projectDbPath) throws SQLException {
        String url = "jdbc:sqlite:" + projectDbPath;
        try (Connection conn = DriverManager.getConnection(url)) {
            // Check if annotation table exists
            boolean hasAnnotations = false;
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='annotations'")) {
                if (rs.next()) hasAnnotations = true;
            }
            if (!hasAnnotations) {
                // No annotation table, needs full processing
                int maxDocId = 0;
                try (ResultSet rs = conn.createStatement().executeQuery("SELECT MAX(document_id) FROM documents")) {
                    if (rs.next()) maxDocId = rs.getInt(1);
                }
                return new AnnotationStatus(true, 1);
            }
            // Find max_annotated_id (where at least one annotation exists)
            int maxAnnotatedId = 0;
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT MAX(document_id) FROM annotations")) {
                if (rs.next()) maxAnnotatedId = rs.getInt(1);
            }
            // Find max_doc_id
            int maxDocId = 0;
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT MAX(document_id) FROM documents")) {
                if (rs.next()) maxDocId = rs.getInt(1);
            }
            boolean needsProcessing = maxAnnotatedId < maxDocId;
            int startDocumentId = (needsProcessing && maxAnnotatedId > 0) ? maxAnnotatedId : 1;
            return new AnnotationStatus(needsProcessing, startDocumentId);
        }
    }

    /**
     * Runs annotation from a specific document ID, deleting any existing annotation/dependency rows for each doc before inserting new ones.
     * Processes up to 'limit' documents in this run (limit is per run, not total).
     * Respects resumability: resumes from the last annotated document.
     */
    public static void runAnnotation(Path projectDbPath, int startDocumentId, int threads, int batchSize, Integer limit) throws Exception {
        String url = "jdbc:sqlite:" + projectDbPath;
        try (Connection conn = DriverManager.getConnection(url)) {
            conn.setAutoCommit(false);
            createTables(conn, false); // Don't drop tables

            // --- Calculate total documents to process (respecting limit and startId) ---
            long totalDocumentsInDb = 0;
            String countQuery = "SELECT COUNT(*) FROM documents WHERE document_id >= ? AND LENGTH(text) <= 15000";
            try (PreparedStatement countStmt = conn.prepareStatement(countQuery)) {
                countStmt.setInt(1, startDocumentId);
                try (ResultSet countRs = countStmt.executeQuery()) {
                    if (countRs.next()) {
                        totalDocumentsInDb = countRs.getLong(1);
                    }
                }
            }

            // Determine the actual number to process in this run based on the limit
            long totalDocumentsToProcessThisRun = (limit != null && limit < totalDocumentsInDb) ? limit : totalDocumentsInDb;

            logger.info("Found {} documents remaining to potentially process (startId={}). This run will process up to {}.",
                        totalDocumentsInDb, startDocumentId, totalDocumentsToProcessThisRun);

            // Query documents to process
            String queryBase = "SELECT document_id, text FROM documents WHERE document_id >= ? AND LENGTH(text) <= 15000 ORDER BY document_id ASC";
            String query = (limit != null) ? queryBase + " LIMIT ?" : queryBase;
            
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setInt(1, startDocumentId);
                if (limit != null) {
                    stmt.setInt(2, limit); // Set the limit parameter
                }
                // Add log before executing the query
                logger.debug("Executing query to fetch documents...");
                try (ResultSet rs = stmt.executeQuery()) {
                    // Add log after query execution starts (inside result set try)
                    logger.debug("Starting document processing loop...");
                    // Initialize the pipeline *once* before the loop
                    StanfordCoreNLP pipeline = createCoreNLPPipeline(threads);
                    logger.debug("CoreNLP pipeline initialized for processing.");

                    int processed = 0;
                    int batch = 0;

                    // Setup progress bar
                    ProgressBarBuilder pbb = new ProgressBarBuilder()
                        .setTaskName("Annotating")
                        .setInitialMax(totalDocumentsToProcessThisRun) // Use count for this run
                        .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                        .setUpdateIntervalMillis(200)
                        .showSpeed();

                    try (ProgressBar pb = pbb.build()) {
                        logger.debug("CoreNLP pipeline initialized. Starting annotation...");
                        while (rs.next()) {
                            // Check if limit has been reached for this run
                            if (limit != null && processed >= limit) {
                                logger.info("Reached processing limit ({}) for this run.", limit);
                                break;
                            }

                            int documentId = rs.getInt("document_id");
                            String text = rs.getString("text");

                            // Delete any existing annotation/dependency rows for this document
                            try (PreparedStatement delAnn = conn.prepareStatement("DELETE FROM annotations WHERE document_id = ?")) {
                                delAnn.setInt(1, documentId);
                                delAnn.executeUpdate();
                            }
                            try (PreparedStatement delDep = conn.prepareStatement("DELETE FROM dependencies WHERE document_id = ?")) {
                                delDep.setInt(1, documentId);
                                delDep.executeUpdate();
                            }

                            // Process and insert new annotation/dependency rows
                            AnnotationResult result = processTextWithCoreNLP(pipeline, text, documentId);
                            insertData(conn, result.annotations, result.dependencies);

                            processed++;
                            pb.step(); // Always step
                            batch++;

                            if (batch >= batchSize) {
                                conn.commit();
                                logger.debug("Committed batch of {} documents", batch);
                                batch = 0;
                            }
                        }
                    } // ProgressBar closes here

                    if (batch > 0) conn.commit();
                }
            }
        }
    }
}
