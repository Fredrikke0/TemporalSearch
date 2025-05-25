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
    private static final int MAX_DOCUMENT_LENGTH = 25000; // Skip very long documents.
    private static final int DOCUMENT_PROCESSING_TIMEOUT_MINUTES = 5; // Timeout for a single CoreNLP task

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
     * Also accepts a force flag to control cleanup behavior.
     */
    public static void runAnnotation(Path projectDbPath, int startDocumentId, int threads, int batchSize, Integer limit, boolean force) throws Exception {
        String url = "jdbc:sqlite:" + projectDbPath;
        try (Connection conn = DriverManager.getConnection(url)) {
            conn.setAutoCommit(false);
            createTables(conn, false); // Don't drop tables

            // Cleanup logic based on force and startDocumentId
            if (force) {
                logger.info("Force flag is true. Deleting existing annotations and dependencies for document_id >= {}", startDocumentId);
                try (PreparedStatement delAnn = conn.prepareStatement("DELETE FROM annotations WHERE document_id >= ?");
                     PreparedStatement delDep = conn.prepareStatement("DELETE FROM dependencies WHERE document_id >= ?")) {
                    
                    delAnn.setInt(1, startDocumentId);
                    delAnn.executeUpdate();

                    delDep.setInt(1, startDocumentId);
                    delDep.executeUpdate();
                    
                    conn.commit(); // Commit the deletions
                } catch (SQLException e) {
                    logger.error("Error during forced delete for document_id >= " + startDocumentId, e);
                    throw e; 
                }
            } else if (startDocumentId > 1) {
                // Clean up only the specific document_id we are starting from
                // in case it was partially processed.
                logger.info("Resuming or starting from specific ID (force=false). Performing cleanup for document_id: {}", startDocumentId);
                try (PreparedStatement delAnn = conn.prepareStatement("DELETE FROM annotations WHERE document_id = ?");
                     PreparedStatement delDep = conn.prepareStatement("DELETE FROM dependencies WHERE document_id = ?")) {
                    
                    delAnn.setInt(1, startDocumentId);
                    delAnn.executeUpdate();

                    delDep.setInt(1, startDocumentId);
                    delDep.executeUpdate();
                    
                    conn.commit();
                } catch (SQLException e) {
                    logger.error("Error during pre-emptive delete for document_id=" + startDocumentId, e);
                    throw e;
                }
            }

            // --- Calculate total documents to process (respecting limit and startId) ---
            // Estimate documents in the current processing scope using MAX(document_id)
            long estimatedDocsInScope = 0;
            String maxIdSql = "SELECT COALESCE(MAX(document_id), 0) FROM documents WHERE document_id >= ? AND LENGTH(text) <= " + MAX_DOCUMENT_LENGTH;
            try (PreparedStatement maxIdStmt = conn.prepareStatement(maxIdSql)) {
                maxIdStmt.setInt(1, startDocumentId);
                try (ResultSet maxIdRs = maxIdStmt.executeQuery()) {
                    if (maxIdRs.next()) {
                        long queriedMaxId = maxIdRs.getLong(1);
                        if (queriedMaxId > 0) {
                            estimatedDocsInScope = (queriedMaxId >= startDocumentId) ? (queriedMaxId - startDocumentId + 1) : 0;
                        }
                    }
                }
            }
            if (estimatedDocsInScope == 0 && startDocumentId == 1) {
                try (PreparedStatement totalCheckStmt = conn.prepareStatement("SELECT COALESCE(MAX(document_id), 0) FROM documents WHERE LENGTH(text) <= " + MAX_DOCUMENT_LENGTH); 
                     ResultSet totalRs = totalCheckStmt.executeQuery()) {
                    if (totalRs.next() && totalRs.getLong(1) == 0) {
                        logger.info("No documents found in the database (or all are too long). Nothing to annotate.");
                        return;
                    }
                }
            }

            // Determine the actual number of documents for the progress bar this run
            long totalDocumentsToProcessThisRun;
            String queryBase = "SELECT document_id, text, timestamp FROM documents WHERE document_id >= ? AND LENGTH(text) <= " + MAX_DOCUMENT_LENGTH + " ORDER BY document_id ASC";
            
            if (limit != null) {
                totalDocumentsToProcessThisRun = limit;
                logger.info("Attempting to process up to {} documents starting from document_id {} (limit specified). Estimated {} documents in available range starting from this ID.", limit, startDocumentId, estimatedDocsInScope);
            } else {
                totalDocumentsToProcessThisRun = estimatedDocsInScope;
                if (estimatedDocsInScope > 0) {
                    logger.info("Attempting to process all {} estimated documents starting from document_id {} (no limit specified).", estimatedDocsInScope, startDocumentId);
                } else {
                    logger.info("No documents found to process at or after document_id {} (no limit specified).", startDocumentId);
                    // If no documents to process, we can return early, progress bar won't even show.
                    if (totalDocumentsToProcessThisRun == 0) return;
                }
            }
            
            // If totalDocumentsToProcessThisRun is 0, nothing to do.
            if (totalDocumentsToProcessThisRun == 0) {
                 logger.info("No documents to process in this run.");
                 return;
            }

            // Use streaming approach with cursor-based pagination
            final int FETCH_CHUNK_SIZE = batchSize * 10; // Fetch in chunks larger than batch size
            int currentStartId = startDocumentId;
            int totalProcessed = 0;
            
            // Setup threading once
            int totalUserThreads = threads;
            int numCoreNLPInternalThreads;
            int numExecutorThreads;

            if (totalUserThreads <= 1) {
                numCoreNLPInternalThreads = 1;
                numExecutorThreads = 1;
            } else {
                // Prioritize executor threads slightly if rounding is an issue, ensure CoreNLP gets at least 1
                numExecutorThreads = Math.max(1, (int) Math.ceil(totalUserThreads * 0.6));
                numCoreNLPInternalThreads = Math.max(1, totalUserThreads - numExecutorThreads);
                if (numCoreNLPInternalThreads == 0) {
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
            int batch = 0;

            ProgressBarBuilder pbb = new ProgressBarBuilder()
                .setTaskName("Annotating")
                .setInitialMax(totalDocumentsToProcessThisRun)
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setUpdateIntervalMillis(200)
                .showSpeed();

            try (ProgressBar pb = pbb.build()) {
                // Process documents in chunks to avoid loading everything into memory
                while (true) {
                    int documentsToFetchThisChunk = FETCH_CHUNK_SIZE;
                    if (limit != null) {
                        int remaining = limit - totalProcessed;
                        if (remaining <= 0) break;
                        documentsToFetchThisChunk = Math.min(FETCH_CHUNK_SIZE, remaining);
                    }
                    
                    String chunkQuery = queryBase + " LIMIT ?";
                    List<DocumentData> documentsChunk = new ArrayList<>();
                    
                    // Fetch a chunk of documents
                    try (PreparedStatement chunkStmt = conn.prepareStatement(chunkQuery)) {
                        chunkStmt.setInt(1, currentStartId);
                        chunkStmt.setInt(2, documentsToFetchThisChunk);
                        
                        try (ResultSet rs = chunkStmt.executeQuery()) {
                            while (rs.next()) {
                                int documentId = rs.getInt("document_id");
                                String text = rs.getString("text");
                                String timestamp = rs.getString("timestamp");
                                
                                documentsChunk.add(new DocumentData(documentId, text, timestamp));
                                currentStartId = documentId + 1; // Update for next chunk
                            }
                        }
                    }
                    
                    // If no documents found, we're done
                    if (documentsChunk.isEmpty()) {
                        logger.debug("No more documents found starting from document_id {}. Processing complete.", currentStartId);
                        break;
                    }
                    
                    logger.debug("Fetched {} documents in chunk starting from document_id {}", documentsChunk.size(), documentsChunk.get(0).documentId);
                    
                    // Process the chunk
                    for (DocumentData doc : documentsChunk) {
                        java.util.concurrent.Future<AnnotationResult> future = executor.submit(() -> {
                            AnnotationResult result = processTextWithCoreNLP(pipeline, doc.text, doc.documentId, doc.timestamp);
                            return result;
                        });
                        futures.add(future);
                        docIds.add(doc.documentId);
                        totalProcessed++;

                        // If enough futures have been submitted, process them as a batch
                        if (futures.size() >= batchSize) {
                            for (int i = 0; i < futures.size(); i++) {
                                java.util.concurrent.Future<AnnotationResult> f = futures.get(i);
                                int docId = docIds.get(i);
                                try {
                                    AnnotationResult result = f.get(DOCUMENT_PROCESSING_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
                                    if (result != null) {
                                        batchResults.add(result);
                                    }
                                } catch (java.util.concurrent.TimeoutException e) {
                                    logger.warn("Timeout ({} min) processing document_id={}. Skipping this document.", DOCUMENT_PROCESSING_TIMEOUT_MINUTES, docId);
                                } catch (Exception e) {
                                    logger.error("Error during annotation task execution for documentId=" + docId, e);
                                    // Optionally, decide if this error should be fatal or rethrown
                                }
                            }
                            
                            // Insert all results in this batch
                            for (AnnotationResult result : batchResults) {
                                insertData(conn, result.annotations, result.dependencies);
                                batch++;
                                pb.step();
                            }
                            
                            // Commit the entire batch
                            if (batch > 0) {
                                conn.commit();
                                logger.trace("Committed batch of {} annotations.", batch);
                                batch = 0;
                            }
                            
                            batchResults.clear();
                            futures.clear();
                            docIds.clear();
                        }
                    }
                    
                    // Clear the chunk from memory immediately
                    documentsChunk.clear();
                    
                    // Check if we've hit the limit
                    if (limit != null && totalProcessed >= limit) {
                        logger.info("Reached processing limit ({}) for this run.", limit);
                        break;
                    }
                }
                
                // Process any remaining futures
                for (int i = 0; i < futures.size(); i++) {
                    java.util.concurrent.Future<AnnotationResult> f = futures.get(i);
                    int docId = docIds.get(i);
                    try {
                        AnnotationResult result = f.get(DOCUMENT_PROCESSING_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
                        if (result != null) {
                            batchResults.add(result);
                        }
                    } catch (java.util.concurrent.TimeoutException e) {
                        logger.warn("Timeout ({} min) processing document_id={} (in remaining batch). Skipping this document.", DOCUMENT_PROCESSING_TIMEOUT_MINUTES, docId);
                    } catch (Exception e) {
                        logger.error("Error during annotation task execution for documentId=" + docId, e);
                        // Optionally, decide if this error should be fatal or rethrown
                    }
                }
                
                // Insert remaining results
                for (AnnotationResult result : batchResults) {
                    insertData(conn, result.annotations, result.dependencies);
                    batch++;
                    pb.step();
                }
                
                // Final commit for any remaining items
                if (batch > 0) {
                    conn.commit();
                    logger.trace("Committed final {} annotations.", batch);
                }
            } finally {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.MINUTES)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
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

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dependencies_document_id ON dependencies (document_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dep_id ON dependencies (dependency_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dep_relation_did_sid_tokens ON dependencies (relation, document_id, sentence_id, head_token, dependent_token)");
            
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

    // Helper class for document data
    private static class DocumentData {
        final int documentId;
        final String text;
        final String timestamp;
        
        DocumentData(int documentId, String text, String timestamp) {
            this.documentId = documentId;
            this.text = text;
            this.timestamp = timestamp;
        }
    }
}

