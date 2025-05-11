package com.example.annotation;

import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.semgraph.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.*;
import java.util.*;
import me.tongfei.progressbar.*;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

public class Annotations {
    private static final Logger logger = LoggerFactory.getLogger(Annotations.class);
    private static final int MAX_DOCUMENT_LENGTH = 20000; // Adjust to reduce annotation time.

    private static StanfordCoreNLP createCoreNLPPipeline(int threads) {
        CoreNLPConfig config = new CoreNLPConfig(threads);
        return config.createPipeline();
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

            // If resuming (startDocumentId > 1), delete existing data for that specific document once.
            // This handles cases where the document might have been partially processed before an interruption.
            if (startDocumentId > 1) {
                logger.info("Resuming, performing cleanup for document_id: {}", startDocumentId);
                try (PreparedStatement delAnn = conn.prepareStatement("DELETE FROM annotations WHERE document_id = ?");
                     PreparedStatement delDep = conn.prepareStatement("DELETE FROM dependencies WHERE document_id = ?")) {
                    
                    delAnn.setInt(1, startDocumentId);
                    delAnn.executeUpdate();

                    delDep.setInt(1, startDocumentId);
                    delDep.executeUpdate();
                    

                } catch (SQLException e) {
                    logger.error("Error during pre-emptive delete for document_id=" + startDocumentId, e);
                    throw e; // Rethrow to halt processing if cleanup fails
                }
            }

            // --- Calculate total documents to process (respecting limit and startId) ---
            long totalDocumentsInDb = 0;
            String countQuery = "SELECT COUNT(*) FROM documents WHERE document_id >= ? AND LENGTH(text) <= " + MAX_DOCUMENT_LENGTH;
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
            String queryBase = "SELECT document_id, text, timestamp FROM documents WHERE document_id >= ? AND LENGTH(text) <= " + MAX_DOCUMENT_LENGTH + " ORDER BY document_id ASC";
            String query = (limit != null) ? queryBase + " LIMIT ?" : queryBase;

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setInt(1, startDocumentId);
                if (limit != null) {
                    stmt.setInt(2, limit); // Set the limit parameter
                }
                logger.debug("Executing query to fetch documents...");
                try (ResultSet rs = stmt.executeQuery()) {
                    logger.debug("Starting document processing loop...");
                    
                    int totalUserThreads = threads; // From -t argument
                    int numCoreNLPInternalThreads;
                    int numExecutorThreads;

                    if (totalUserThreads <= 1) {
                        numCoreNLPInternalThreads = 1;
                        numExecutorThreads = 1;
                    } else {
                        // Prioritize executor threads slightly if rounding is an issue, ensure CoreNLP gets at least 1
                        numExecutorThreads = Math.max(1, (int) Math.ceil(totalUserThreads * 0.6));
                        numCoreNLPInternalThreads = Math.max(1, totalUserThreads - numExecutorThreads);
                        // If coreNLP threads ended up 0 due to the above, give it at least 1 and adjust executor
                        if (numCoreNLPInternalThreads == 0) { // Should not happen with Math.max(1, ...) above but as a safeguard
                            numCoreNLPInternalThreads = 1;
                            numExecutorThreads = Math.max(1, totalUserThreads - 1);
                        }
                    }

                    StanfordCoreNLP pipeline = createCoreNLPPipeline(numCoreNLPInternalThreads);
                    logger.debug("CoreNLP pipeline initialized for processing with {} internal threads.", numCoreNLPInternalThreads);
                    logger.debug("ExecutorService configured for {} parallel document tasks.", numExecutorThreads);

                    ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(numExecutorThreads);
                    List<java.util.concurrent.Future<AnnotationResult>> futures = new ArrayList<>();
                    List<Integer> docIds = new ArrayList<>();
                    List<AnnotationResult> batchResults = new ArrayList<>();
                    int processed = 0;
                    int batch = 0;

                    ProgressBarBuilder pbb = new ProgressBarBuilder()
                        .setTaskName("Annotating")
                        .setInitialMax(totalDocumentsToProcessThisRun)
                        .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                        .setUpdateIntervalMillis(200)
                        .showSpeed();

                    try (ProgressBar pb = pbb.build()) {
                    while (rs.next()) {
                            if (limit != null && processed >= limit) {
                                logger.info("Reached processing limit ({}) for this run.", limit);
                                break;
                            }
                        int documentId = rs.getInt("document_id");
                        String text = rs.getString("text");
                        String timestamp = rs.getString("timestamp"); // Get the timestamp

                            java.util.concurrent.Future<AnnotationResult> future = executor.submit(() -> {
                        AnnotationResult result = processTextWithCoreNLP(pipeline, text, documentId, timestamp);
                                return result;
                            });
                            futures.add(future);
                            docIds.add(documentId);
                            processed++;

                            // If enough futures for a batch, process them
                            if (futures.size() >= batchSize) {
                                for (int i = 0; i < futures.size(); i++) {
                                    java.util.concurrent.Future<AnnotationResult> f = futures.get(i);
                                    int docId = docIds.get(i);
                                    try {
                                        AnnotationResult result = f.get();
                                        batchResults.add(result);
                                    } catch (Exception e) {
                                        logger.error("Error during annotation task execution for documentId=" + docId, e);
                                        throw e;
                                    }
                                }
                                // Insert all results in this batch
                                for (int i = 0; i < batchResults.size(); i++) {
                                    int docId = docIds.get(i);
                                    AnnotationResult result = batchResults.get(i);
                        insertData(conn, result.annotations, result.dependencies);
                                    batch++;
                        pb.step();
                                }
                            conn.commit();
                                batch = 0;
                                batchResults.clear();
                                futures.clear();
                                docIds.clear();
                            }
                        }
                        // Process any remaining futures
                        for (int i = 0; i < futures.size(); i++) {
                            java.util.concurrent.Future<AnnotationResult> f = futures.get(i);
                            int docId = docIds.get(i);
                            try {
                                AnnotationResult result = f.get();
                                batchResults.add(result);
                            } catch (Exception e) {
                                logger.error("Error during annotation task execution for documentId=" + docId, e);
                                throw e;
                            }
                        }
                        for (int i = 0; i < batchResults.size(); i++) {
                            int docId = docIds.get(i);
                            AnnotationResult result = batchResults.get(i);
                            insertData(conn, result.annotations, result.dependencies);
                            batch++;
                            pb.step();
                        }
                        if (batch > 0) {
                        conn.commit();
                        }
                    } finally {
                        executor.shutdown();
                        try {
                            if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES)) {
                                executor.shutdownNow();
                            }
                        } catch (InterruptedException e) {
                            executor.shutdownNow();
                            Thread.currentThread().interrupt();
                        }
                    }
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

            // Indexes for annotations table
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_annotations_document_id ON annotations (document_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ann_did_sid_token_lemma ON annotations (document_id, sentence_id, token, lemma)");

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
            // Indexes for dependencies table
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dependencies_document_id ON dependencies (document_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dep_id ON dependencies (dependency_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dep_relation_did_sid_tokens ON dependencies (relation, document_id, sentence_id, head_token, dependent_token)");
            
            // Assuming 'documents' table exists and this is a good place to ensure its index
            // If 'documents' table creation is handled elsewhere, this might be redundant or ideally co-located.
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_doc_id_timestamp ON documents (document_id, timestamp)");
        }
    }

    /**
     * Processes text with CoreNLP pipeline, and returns annotations.
     * 
     * @param pipeline The CoreNLP pipeline to use for processing
     * @param text The text to process
     * @param documentId The ID of the document being processed
     * @return The AnnotationResult containing annotations and dependencies
     */
    private static AnnotationResult processTextWithCoreNLP(StanfordCoreNLP pipeline, String text, int documentId, String documentTimestamp) {
        List<Map<String, Object>> annotations = new ArrayList<>();
        List<Map<String, Object>> dependencies = new ArrayList<>();
        
        CoreDocument document = new CoreDocument(text);

        // Set the document date for SUTime to resolve relative dates like "yesterday"
        if (documentTimestamp != null && !documentTimestamp.isEmpty()) {
            // Assuming timestamp is like "YYYY-MM-DD HH:MM:SS" or just "YYYY-MM-DD"
            // CoreNLP's DocDateAnnotation expects "YYYY-MM-DD"
            String dateOnly = documentTimestamp.length() > 10 ? documentTimestamp.substring(0, 10) : documentTimestamp;
            if (dateOnly.matches("\\d{4}-\\d{2}-\\d{2}")) { // Basic check for YYYY-MM-DD format
                 document.annotation().set(CoreAnnotations.DocDateAnnotation.class, dateOnly);
                 logger.trace("Set DocDateAnnotation to: {} for document_id: {}", dateOnly, documentId);
            } else {
                logger.warn("Timestamp format for document_id: {} ('{}') is not YYYY-MM-DD. SUTime might not use it correctly.", documentId, dateOnly);
            }
        }
        
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
}
