package com.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.annotation.Annotations;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

public class Pipeline {
    private static final Logger logger = LoggerFactory.getLogger(Pipeline.class);

    public static void main(String[] args) {
        try {
            runPipeline(args);
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
        ArgumentParser parser = ArgumentParsers.newFor("Pipeline").build()
                .defaultHelp(true)
                .description("Process and index text data through annotation and indexing stages.")
                .usage("${prog} --db-file <database_file_path> --index-dir <index_directory_path> -s <stage> [--force] [--limit N] [stage-specific-options]\n\n" +
                       "Example usage:\n" +
                       "  Create Project & Run All: ${prog} --db-file path/to/source.db --index-dir path/to/my_project_outputs -s all\n" +
                       "  Annotate Existing Project: ${prog} --db-file path/to/source.db --index-dir path/to/my_project_outputs -s annotate -b 1000 -t 8\n" +
                       "  Index Existing Project (force): ${prog} --db-file path/to/source.db --index-dir path/to/my_project_outputs -s index -y bigram --force");

        parser.addArgument("-s", "--stage")
                .choices("all", "annotate", "index")
                .required(true)
                .help("Pipeline stage(s) to run: " +
                      "all - Run annotation and indexing; " +
                      "annotate - Annotate documents; " +
                      "index - Generate indexes from annotations");

        var pathsGroup = parser.addArgumentGroup("Path arguments");
        pathsGroup.addArgument("--db-file")
                .dest("db_file_path")
                .required(true)
                .help("Path to the SQLite database file.");

        pathsGroup.addArgument("--index-dir")
                .dest("index_dir_path")
                .required(true)
                .help("Path to the directory where indexes and temporary processing files will be stored.");

        var commonOptsGroup = parser.addArgumentGroup("Common optional arguments");
        commonOptsGroup.addArgument("--force")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .help("Force re-running the requested stages, overwriting existing artifacts (annotations, indexes).");

        commonOptsGroup.addArgument("-l", "--limit")
                .type(Integer.class)
                .help("Maximum documents to process in the ANNOTATION stage per run (does not count already annotated documents).");

        commonOptsGroup.addArgument("--start-doc-id")
                .dest("cli_start_doc_id")
                .type(Integer.class)
                .required(false)
                .help("Specify the document_id from which to start annotation. Overrides resume logic if provided.");

        var annotateGroup = parser.addArgumentGroup("Annotation stage arguments (used in 'annotate' or 'all' stage)");
        annotateGroup.addArgument("-b", "--batch-size")
                .setDefault(300)
                .type(Integer.class)
                .help("Number of documents to commit per transaction during annotation");

        annotateGroup.addArgument("-t", "--threads")
                .setDefault(Runtime.getRuntime().availableProcessors())
                .type(Integer.class)
                .help("Number of parallel threads for CoreNLP processing.");

        annotateGroup.addArgument("--fix-document-ids")
                .dest("fix_ids")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .setDefault(false)
                .help("Re-number document_ids of unannotated documents to be higher than existing ones before annotation. " +
                      "Useful if annotating previously skipped long documents to maintain ID order for indexing.");

        // Index stage group
        var indexGroup = parser.addArgumentGroup("Index stage arguments (used in 'index' or 'all' stage)");
        indexGroup.addArgument("-w", "--stopwords")
                .setDefault("stopwords.txt")
                .help("Path to file containing stopwords to exclude.");

        indexGroup.addArgument("--idx-batch-size")
                .setDefault(200000)
                .type(Integer.class)
                .help("Number of documents to fetch from DB at a time by an index generator.");

        indexGroup.addArgument("-y", "--index-type")
                .choices("unigram", "bigram", "trigram", "dependency", "ner_date", "ner", "pos", "hypernym", "nash", "all", "stitches")
                .setDefault(java.util.List.of("all"))
                .nargs("+")
                .help("Type of index to generate (can specify multiple, space-separated): " +
                      "unigram - Single word index; " +
                      "bigram - Two word phrases; " +
                      "trigram - Three word phrases; " +
                      "dependency - Grammatical dependencies; " +
                      "ner_date - Named entity dates; " +
                      "ner - Named entity recognition; " +
                      "pos - Part-of-speech tagging; " +
                      "hypernym - Word hypernyms; " +
                      "nash - Efficient index for searching for dates; " +
                      "stitches - Generates all N-gram/Annotation stitch combinations (e.g., bigram-date, unigram-ner, etc.); " +
                      "all - Generate all available index types.");

        indexGroup.addArgument("--custom-temp-dir")
                .dest("custom_temp_dir")
                .type(String.class)
                .required(false)
                .help("Path to a custom base directory for temporary files during index generation. If not specified, defaults to '<index_dir_path>/indexes/temp/'.");

        Namespace ns;
        try {
            ns = parser.parseArgs(args);
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            return;
        }

        // --- Argument Processing and Validation ---
        String stage = ns.getString("stage");
        Path dbFilePath = Path.of(ns.getString("db_file_path")).toAbsolutePath();
        Path indexDirPath = Path.of(ns.getString("index_dir_path")).toAbsolutePath();
        Path indexBasePath = indexDirPath.resolve("indexes");

        boolean force = ns.getBoolean("force");
        Integer limit = ns.getInt("limit");
        Integer cliStartDocId = ns.get("cli_start_doc_id");
        boolean fixIds = ns.getBoolean("fix_ids");

        java.util.List<String> cliRequestedIndexTypes = ns.getList("index_type"); // Renamed for clarity

        logger.info("Starting Pipeline (DB: '{}', Index Dir: '{}', Stage: {}, CLI Requested Index Types: {})",
                    dbFilePath, indexDirPath, stage, cliRequestedIndexTypes);

        // --- Project Initialization & Validation ---

        // Validate database file existence
        if (!Files.exists(dbFilePath)) {
            logger.error("Database file not found: {}", dbFilePath.toAbsolutePath());
            throw new IOException("Database file not found: " + dbFilePath.toAbsolutePath());
        }
        logger.debug("Using database file: {}", dbFilePath.toAbsolutePath());

        // Create index directory and its 'indexes' subdirectory if they don't exist
        if (!Files.exists(indexDirPath)) {
            logger.info("Index directory '{}' does not exist. Creating...", indexDirPath.toAbsolutePath());
            Files.createDirectories(indexDirPath);
        } else {
            logger.debug("Using existing index directory '{}'", indexDirPath.toAbsolutePath());
        }

        if (!Files.exists(indexBasePath)) {
            logger.info("Base indexes directory '{}' does not exist within index directory. Creating...", indexBasePath.toAbsolutePath());
            Files.createDirectories(indexBasePath);
        } else {
            logger.debug("Using existing base indexes directory '{}'", indexBasePath.toAbsolutePath());
        }

        // --- Stage Execution ---

        // Run annotation stage if requested ('all' or 'annotate')
        if (stage.equals("all") || stage.equals("annotate")) {
            logger.info("--- Annotation Stage ---");
            logger.debug("About to run annotation on DB: {}", dbFilePath.toAbsolutePath());
            int threads = ns.getInt("threads");
            int batchSize = ns.getInt("batch_size"); // Used for commit frequency

            Annotations.AnnotationStatus status = Annotations.getAnnotationStatus(dbFilePath);

            if (force || status.needsProcessing) {
                int startId;
                if (cliStartDocId != null) {
                    startId = cliStartDocId;
                    logger.info("Using command-line specified --start-doc-id: {}", startId);
                    if (force) {
                        logger.info("--force is also active. Annotation will start from {} and overwrite existing annotations from this ID onwards.", startId);
                    }
                } else if (force) {
                    startId = 1;
                    logger.info("--force active, starting annotation from document_id 1.");
                } else {
                    startId = status.startDocumentId;
                    logger.info("Resuming annotation based on status, starting from document_id: {}", startId);
                }

                logger.info("Starting annotation (startDocumentId={}, force={}, limit={}, threads={}, batchSize={}, fixIds={})",
                            startId, force, limit == null ? "none" : limit, threads, batchSize, fixIds);
                // Ensure limit is passed correctly
                Annotations.runAnnotation(dbFilePath, startId, threads, batchSize, limit, force, fixIds);
                logger.info("Annotation stage completed.");
            } else {
                logger.info("Annotation already complete according to status check. Skipping. Use --force to re-annotate.");
            }
        }

        // Run indexing stage if requested ('all' or 'index')
        if (stage.equals("all") || stage.equals("index")) {
            logger.info("--- Indexing Stage ---");

            // Determine the effective set of index types for Pipeline's own logic (e.g., cleanup)
            // This logic mirrors the one in IndexRunner for consistency.
            java.util.Set<String> typesForPipelineLogic = new java.util.LinkedHashSet<>();
            if (cliRequestedIndexTypes.contains("all")) {
                typesForPipelineLogic.addAll(java.util.List.of(
                    "unigram", "bigram", "trigram", "dependency", "hypernym",
                    "ner_date", "pos", "ner", "nash", "stitches"
                ));
            } else {
                typesForPipelineLogic.addAll(cliRequestedIndexTypes);
            }

            if (typesForPipelineLogic.contains("stitches")) {
                typesForPipelineLogic.addAll(java.util.List.of(
                    "unigram", "bigram", "trigram", "pos", "ner", "ner_date"
                ));
                 typesForPipelineLogic.add("stitches"); // Ensure it's there
            }

            // Convert all to lowercase for consistency in pipeline logic
            java.util.Set<String> effectiveIndexTypesForPipeline = typesForPipelineLogic.stream()
                                                                    .map(String::toLowerCase)
                                                                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

            logger.debug("Effective index types for Pipeline internal logic (e.g. cleanup): {}", effectiveIndexTypesForPipeline);

            String stopwordsPath = ns.getString("stopwords");
            int indexBatchSize = ns.getInt("idx_batch_size");
            String customTempDirArg = ns.getString("custom_temp_dir");

            String effectiveCustomTempDirStr;
            if (customTempDirArg != null && !customTempDirArg.isBlank()) {
                effectiveCustomTempDirStr = customTempDirArg;
                logger.info("Using user-provided custom temporary directory: {}", effectiveCustomTempDirStr);
            } else {
                Path defaultTempPath = indexBasePath.resolve("temp");
                effectiveCustomTempDirStr = defaultTempPath.toString();
                logger.info("Using default temporary directory for indexing: {}", effectiveCustomTempDirStr);
                Path defaultTempDir = Path.of(effectiveCustomTempDirStr);
                if (!Files.exists(defaultTempDir)) {
                    Files.createDirectories(defaultTempDir);
                    logger.info("Created default temporary directory: {}", defaultTempDir.toAbsolutePath());
                }
            }

            if (force) {
                 logger.info("--force specified. Indexing will proceed and overwrite existing data. Cleanup handled by Pipeline before calling IndexRunner.");

                 if (cliRequestedIndexTypes.contains("all")) { // Use cliRequested for the 'all' check for outer cleanup
                     logger.warn("Deleting all contents of index base directory due to --force and 'all' type: {}", indexBasePath.toAbsolutePath());
                     deleteDirectoryRecursively(indexBasePath);
                     Files.createDirectories(indexBasePath);
                 } else {
                     for (String typeToClean : effectiveIndexTypesForPipeline) { // Iterate using the expanded set for pipeline logic
                         if (typeToClean.equalsIgnoreCase("stitches")) {
                            logger.warn("--force specified for stitches. Deleting all potential stitch output directories and temp_stitch_gen directory.");
                            for (com.example.index.NgramType nt : com.example.index.NgramType.values()) {
                                for (com.example.index.AnnotationTypeSource ats : com.example.index.AnnotationTypeSource.values()) {
                                    String dirName = "stitch_" + nt.name().toLowerCase() + "_" + ats.getTypeIdentifier();
                                    Path dirToDelete = indexBasePath.resolve(dirName);
                                    deleteDirectoryRecursively(dirToDelete);
                                }
                            }
                            Path tempStitchGenDir = indexBasePath.resolve("temp_stitch_gen");
                            deleteDirectoryRecursively(tempStitchGenDir);
                         } else {
                             Path specificIndexDirToClean = indexBasePath.resolve(typeToClean);
                             if (Files.exists(specificIndexDirToClean)) {
                                 logger.warn("Deleting existing index directory due to --force: {}", specificIndexDirToClean.toAbsolutePath());
                                 deleteDirectoryRecursively(specificIndexDirToClean);
                             }
                         }
                     }
                 }
            } else {
                logger.info("Pipeline called without --force. IndexRunner will check individual index directories for existence and decide whether to generate/skip.");
            }

            logger.info("Calling IndexRunner (CLI requested types={}, stopwords='{}', batchSize={}, customTempDir='{}', force={})",
                        cliRequestedIndexTypes, stopwordsPath, indexBatchSize, effectiveCustomTempDirStr, force);
            IndexRunner.runIndexing(
                dbFilePath.toString(),
                indexBasePath.toString(),
                stopwordsPath,
                indexBatchSize,
                cliRequestedIndexTypes, // Pass the original CLI requested types
                effectiveCustomTempDirStr,
                force
            );
            logger.info("Indexing stage completed.");
        }

        logger.info("Pipeline completed successfully!");
    }

    // Helper method to delete directories recursively
    private static void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path)) { // Check if path exists before attempting to delete
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.sorted((a, b) -> b.compareTo(a))
                        .forEach(subPath -> {
                            try { Files.deleteIfExists(subPath); }
                            catch (IOException e) { logger.error("Could not delete path '{}': {}", subPath.toAbsolutePath(), e.getMessage(), e); }
                        });
                }
            } else {
                Files.deleteIfExists(path);
            }
            logger.debug("Successfully deleted: {}", path.toAbsolutePath());
        } else {
            logger.trace("Path to delete does not exist, skipping deletion: {}", path.toAbsolutePath());
        }
    }
}
