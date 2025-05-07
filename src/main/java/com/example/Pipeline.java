package com.example;

import com.example.annotation.Annotations;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.nio.file.InvalidPathException;
import java.util.stream.Stream;

public class Pipeline {
    private static final Logger logger = LoggerFactory.getLogger(Pipeline.class);

    // Method to validate the database schema
    private static void validateSourceDatabaseSchema(Path dbPath, ArgumentParser parser) throws IOException, SQLException, ArgumentParserException {
        logger.debug("Validating database schema for: {}", dbPath.toAbsolutePath());
        String connectionUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath().toString();
        try (Connection conn = DriverManager.getConnection(connectionUrl)) {
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet tables = meta.getTables(null, null, "documents", null);
            if (!tables.next()) {
                String errorMsg = String.format("Required 'documents' table not found in database: %s", dbPath.toAbsolutePath());
                logger.error(errorMsg);
                // Using ArgumentParserException to provide context and potentially halt execution cleanly
                throw new ArgumentParserException(errorMsg, parser);
            }
            tables.close();

            List<String> requiredColumns = Arrays.asList("document_id", "title", "text", "timestamp");
            HashSet<String> foundColumns = new HashSet<>();
            ResultSet columns = meta.getColumns(null, null, "documents", null);
            while (columns.next()) {
                foundColumns.add(columns.getString("COLUMN_NAME").toLowerCase());
            }
            columns.close();

            for (String col : requiredColumns) {
                if (!foundColumns.contains(col.toLowerCase())) {
                    String errorMsg = String.format("Required column '%s' not found in 'documents' table in database: %s", col, dbPath.toAbsolutePath());
                    logger.error(errorMsg);
                    throw new ArgumentParserException(errorMsg, parser);
                }
            }
            // Basic type check for title and text (can be expanded)
            // This is a simplified check; SQLite types are flexible. We mainly care they exist.
            // More specific type validation can be complex due to SQLite's type affinity.
            logger.info("Database schema validation successful for: {}", dbPath.toAbsolutePath());
        } catch (SQLException e) {
            logger.error("SQL error during database schema validation for {}: {}", dbPath.toAbsolutePath(), e.getMessage(), e);
            throw e; // Re-throw SQLException
        }
    }

    public static void main(String[] args) {
        try {
            runPipeline(args);
        } catch (ArgumentParserException e) {
            System.err.println("Argument Error: " + e.getMessage());
            System.exit(1);
        } catch (InvalidPathException e) {
            logger.error("Invalid path provided: {}", e.getMessage(), e);
            System.err.println("Error: Invalid path specified - " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            logger.error("File operation failed: {}", e.getMessage(), e);
            System.err.println("Error during file operation: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            logger.error("Error running pipeline", e);
            System.err.println("Error running pipeline: " + e.getMessage());
            System.exit(1);
        }
    }

    public static void runPipeline(String[] args) throws Exception {
        // Create argument parser
        ArgumentParser parser = ArgumentParsers.newFor("Pipeline").build()
                .defaultHelp(true)
                .description("Process and index text data through annotation and indexing stages using a project-based workflow.")
                .usage("${prog} -p <project_path> -s <stage> [-d <source_db>] [--force] [--limit N] [stage-specific-options]\n\n" + 
                       "Example usage:\n" +
                       "  Create Project & Run All: ${prog} -p path/to/my_project -d source.db -s all\n" +
                       "  Annotate Existing Project: ${prog} -p path/to/my_project -s annotate -b 1000 -t 8\n" +
                       "  Index Existing Project (force): ${prog} -p path/to/my_project -s index -y bigram --force");

        // Stage argument (moved higher as it dictates required args)
        parser.addArgument("-s", "--stage")
                .choices("all", "annotate", "index")
                .required(true) // Stage is now mandatory
                .help("Pipeline stage(s) to run:" +
                      "  all      - Run annotation and indexing" +
                      "  annotate - Annotate documents" +
                      "  index    - Generate indexes from annotationsp");

        // Project arguments group
        var projectGroup = parser.addArgumentGroup("Project arguments");
        projectGroup.addArgument("-p", "--project")
                .dest("project_path") // Store in 'project_path'
                .required(true)
                .help("Path to the project directory. Will be created if it doesn't exist.");

        projectGroup.addArgument("-d", "--database")
                .dest("source_db_path") // Store in 'source_db_path'
                .required(false) // Required only if project dir doesn't exist (validated later)
                .help("Path to the pre-converted source SQLite database. Required only if the project directory needs to be created. Will be copied into the project directory.");

        // Common optional arguments
        var commonOptsGroup = parser.addArgumentGroup("Common optional arguments");
        commonOptsGroup.addArgument("--force")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .help("Force re-running the requested stages, overwriting existing artifacts (annotations, indexes).");

        commonOptsGroup.addArgument("-l", "--limit")
                .type(Integer.class)
                .help("Maximum documents to process in the ANNOTATION stage per run (does not count already annotated documents).");

        // Annotation stage group
        var annotateGroup = parser.addArgumentGroup("Annotation stage arguments (used in 'annotate' or 'all' stage)");
        annotateGroup.addArgument("-b", "--batch-size")
                .setDefault(1000) // Keep batch size for annotation commit frequency
                .type(Integer.class)
                .help("Number of documents to commit per transaction during annotation (default: 1000)");

        annotateGroup.addArgument("-t", "--threads")
                .setDefault(Runtime.getRuntime().availableProcessors()) // Default to available processors
                .type(Integer.class)
                .help("Number of parallel threads for CoreNLP processing (default: available processors)");

        // Index stage group
        var indexGroup = parser.addArgumentGroup("Index stage arguments (used in 'index' or 'all' stage)");
        indexGroup.addArgument("-w", "--stopwords")
                .setDefault("stopwords.txt")
                .help("Path to file containing stopwords to exclude (default: stopwords.txt)");

        indexGroup.addArgument("-y", "--index-type")
                .choices("unigram", "bigram", "trigram", "dependency", "ner_date", "ner", "pos", "hypernym", "stitch", "nash", "all") // Added 'nash'
                .setDefault("all")
                .help("Type of index to generate:" +
                      "  unigram    - Single word index" +
                      "  bigram     - Two word phrases" +
                      "  trigram    - Three word phrases" +
                      "  dependency - Grammatical dependencies" +
                      "  ner_date   - Named entity dates" +
                      "  ner        - Named entity recognition" +
                      "  pos        - Part-of-speech tagging" +
                      "  hypernym   - Word hypernyms" +
                      "  stitch     - Connects unigrams with their associated dates" +
                      "  nash       - Specific index type (adjust description if needed)" + // Added nash
                      "  all        - Generate all available index types (default)");

        // Parse arguments
        Namespace ns = parser.parseArgs(args);
        
        // --- Argument Processing and Validation ---
        String stage = ns.getString("stage");
        Path projectPath = Path.of(ns.getString("project_path")).toAbsolutePath(); // Ensure absolute path
        String projectName = projectPath.getFileName().toString(); // Derive project name from path
        Path projectDbPath = projectPath.resolve(projectName + ".db");
        Path indexBasePath = projectPath.resolve("indexes");
        String sourceDbPathStr = ns.getString("source_db_path");
        boolean force = ns.getBoolean("force");
        Integer limit = ns.getInt("limit");
        
        logger.info("Starting Pipeline for project '{}' at '{}' (Stage: {})", projectName, projectPath, stage);

        // --- Project Initialization ---
        boolean projectExists = Files.exists(projectPath);
        if (!projectExists) {
            logger.info("Project directory '{}' does not exist. Creating...", projectPath.toAbsolutePath());
            if (sourceDbPathStr == null) {
                throw new ArgumentParserException("Source database path (--database / -d) is required when creating a new project.", parser);
            }
            Path sourceDbPath = Path.of(sourceDbPathStr);
            logger.info("Source DB to copy: {}", sourceDbPath.toAbsolutePath());
            if (!Files.exists(sourceDbPath)) {
                throw new IOException("Source database file not found: " + sourceDbPath);
            }

            // Create project directory and indexes subdirectory
            Files.createDirectories(projectPath);
            Files.createDirectories(indexBasePath);

            // Copy source database to project database path
            logger.info("Copying source database from '{}' to '{}'", sourceDbPath.toAbsolutePath(), projectDbPath.toAbsolutePath());
            Files.copy(sourceDbPath, projectDbPath, StandardCopyOption.REPLACE_EXISTING);
            
            // Validate the newly copied project database
            validateSourceDatabaseSchema(projectDbPath, parser);

            logger.info("Project '{}' created successfully. Project DB at: {}", projectName, projectDbPath.toAbsolutePath());
        } else {
            logger.info("Using existing project directory '{}'", projectPath.toAbsolutePath());
            if (sourceDbPathStr != null) {
                logger.warn("Source database path (--database / -d) provided but project directory already exists. Ignoring source database argument. Project dir: {}", projectPath.toAbsolutePath());
            }
            if (!Files.exists(projectDbPath)) {
                 // If project dir exists but DB is missing (and not creating), it's an error
                 logger.error("Project directory exists, but the project database file is missing: {}", projectDbPath.toAbsolutePath());
                 throw new IOException("Project directory exists, but the project database file is missing: " + projectDbPath.toAbsolutePath());
            }
            // Validate the existing project database
            validateSourceDatabaseSchema(projectDbPath, parser);

             // Ensure indexes directory exists even for existing projects
            if (!Files.exists(indexBasePath)) {
                 logger.warn("Indexes directory missing in existing project. Creating '{}'", indexBasePath.toAbsolutePath());
                 Files.createDirectories(indexBasePath);
            }
        }

        // Log final paths being used
        logger.debug("Using Project Database: {}", projectDbPath.toAbsolutePath());
        logger.debug("Using Index Base Directory: {}", indexBasePath.toAbsolutePath());


        // --- Stage Execution ---

        // Run annotation stage if requested ('all' or 'annotate')
        if (stage.equals("all") || stage.equals("annotate")) {
            logger.info("--- Annotation Stage ---");
            logger.debug("About to run annotation on DB: {}", projectDbPath.toAbsolutePath());
            int threads = ns.getInt("threads");
            int batchSize = ns.getInt("batch_size"); // Used for commit frequency

            Annotations.AnnotationStatus status = Annotations.getAnnotationStatus(projectDbPath);

            if (force || status.needsProcessing) {
                int startId = force ? 1 : status.startDocumentId; // Start from 1 if forcing
                logger.info("Starting annotation (startDocumentId={}, force={}, limit={}, threads={}, batchSize={})",
                            startId, force, limit == null ? "none" : limit, threads, batchSize);
                // Ensure limit is passed correctly
                Annotations.runAnnotation(projectDbPath, startId, threads, batchSize, limit);
                logger.info("Annotation stage completed.");
            } else {
                logger.info("Annotation already complete according to status check. Skipping. Use --force to re-annotate.");
            }
        }

        // Run indexing stage if requested ('all' or 'index')
        if (stage.equals("all") || stage.equals("index")) {
            logger.info("--- Indexing Stage ---");
            logger.debug("About to run indexing on DB: {}", projectDbPath.toAbsolutePath());
            String indexType = ns.getString("index_type");
            String stopwordsPath = ns.getString("stopwords");
            int indexBatchSize = 1000; // Default batch size for indexer internal operations, if needed by IndexRunner later.

            // Determine the specific index directory path
            Path specificIndexDir = indexBasePath.resolve(indexType.equals("all") ? "" : indexType); // Base path if 'all'

             // Check if the specific index type needs processing
            boolean needsIndexing = true; // Assume yes unless we check
            if (!force) {
                if (indexType.equals("all")) {
                    logger.info("Index type 'all' selected without --force. Existing individual indexes might be regenerated by the indexer if it doesn't skip internally.");
                    needsIndexing = true; // Proceed with the call
                } else {
                    if (Files.exists(specificIndexDir)) {
                       logger.info("Index directory for type '{}' already exists: '{}'. Skipping. Use --force to regenerate.", indexType, specificIndexDir.toAbsolutePath());
                       needsIndexing = false;
                    } else {
                       logger.info("Index directory for type '{}' does not exist. Proceeding with generation.", indexType);
                       needsIndexing = true;
                    }
                }
            } else {
                 logger.info("--force specified. Indexing will proceed and overwrite existing data.");
                 needsIndexing = true;
                 if (!indexType.equals("all") && Files.exists(specificIndexDir)) {
                     logger.warn("Deleting existing index directory due to --force: {}", specificIndexDir.toAbsolutePath());
                     try (Stream<Path> walk = Files.walk(specificIndexDir)) {
                         walk.sorted((a, b) -> b.compareTo(a)) // Reverse order for deletion
                             .forEach(path -> {
                                 try {
                                     Files.deleteIfExists(path);
                                 } catch (IOException e) {
                                     logger.error("Could not delete path '{}': {}", path.toAbsolutePath(), e.getMessage(), e);
                                 }
                             });
                         logger.info("Successfully deleted existing index directory: {}", specificIndexDir.toAbsolutePath());
                     } catch (IOException e) {
                          logger.error("Error walking directory tree for deletion '{}': {}", specificIndexDir.toAbsolutePath(), e.getMessage(), e);
                          throw new IOException("Failed to delete existing index directory before forced regeneration: " + specificIndexDir.toAbsolutePath(), e);
                     }
                 } else if (indexType.equals("all") && Files.exists(indexBasePath)) {
                     logger.warn("Deleting all contents of index base directory due to --force: {}", indexBasePath.toAbsolutePath());
                     try (Stream<Path> walk = Files.list(indexBasePath)) { // Only list immediate children
                          walk.forEach(path -> {
                              try {
                                  if (Files.isDirectory(path)) {
                                       try (Stream<Path> subWalk = Files.walk(path)) {
                                            subWalk.sorted((a, b) -> b.compareTo(a))
                                                 .forEach(subPath -> {
                                                     try { Files.deleteIfExists(subPath); }
                                                     catch (IOException e) { logger.error("Could not delete path '{}': {}", subPath.toAbsolutePath(), e.getMessage(), e); }
                                                 });
                                       }
                                  } else {
                                      Files.deleteIfExists(path); // Delete files directly
                                  }
                                  logger.info("Deleted existing index artifact: {}", path.toAbsolutePath());
                              } catch (IOException e) {
                                  logger.error("Could not delete path '{}': {}", path.toAbsolutePath(), e.getMessage(), e);
                              }
                          });
                          logger.info("Successfully deleted contents of index base directory: {}", indexBasePath.toAbsolutePath());
                     } catch (IOException e) {
                         logger.error("Error clearing index base directory '{}': {}", indexBasePath.toAbsolutePath(), e.getMessage(), e);
                         throw new IOException("Failed to clear index base directory before forced 'all' regeneration: " + indexBasePath.toAbsolutePath(), e);
                     }
                 }
            }


            if (needsIndexing) {
                 logger.info("Running Indexer (type={}, stopwords='{}', batchSize={})",
                             indexType, stopwordsPath, indexBatchSize);
                 IndexRunner.runIndexing(
                     projectDbPath.toString(),
                     indexBasePath.toString(), // Pass base index dir
                     stopwordsPath,
                     indexBatchSize, // Pass default/parsed batch size
                     indexType // Pass specific type ('all' or single)
                 );
                 logger.info("Indexing stage completed.");
            }
        }

        logger.info("Pipeline completed successfully for project '{}'!", projectName);
    }
}
