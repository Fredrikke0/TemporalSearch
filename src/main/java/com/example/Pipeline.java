package com.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.annotation.Annotations;
import com.example.project.ProjectManifest;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

public class Pipeline {
    private static final Logger logger = LoggerFactory.getLogger(Pipeline.class);

    private static final java.util.List<String> ALL_STITCH_OUTPUT_TYPES = java.util.List.of(
        "stitch_unigram_date", "stitch_unigram_ner", //"stitch_unigram_pos",
        "stitch_bigram_date", "stitch_bigram_ner", //"stitch_bigram_pos",
        "stitch_trigram_date", "stitch_trigram_ner" //"stitch_trigram_pos"
    );

    private static final java.util.List<String> ALL_NON_STITCH_INDEX_TYPES = java.util.List.of(
        "unigram", "bigram", "trigram", "dependency",
        "ner_date", "pos", "ner", "nash"
    );
    private static final String TEMP_STITCH_GEN_DIR_NAME = "temp_stitch_gen";

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
                .setDefault(800)
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
                .setDefault(500000)
                .type(Integer.class)
                .help("Number of documents to fetch from DB at a time by an index generator.");

        java.util.List<String> allPossibleIndexTypes = new java.util.ArrayList<>();
        allPossibleIndexTypes.add("all");
        allPossibleIndexTypes.add("stitches"); // Meta-type for all stitch combinations
        allPossibleIndexTypes.addAll(ALL_NON_STITCH_INDEX_TYPES);
        allPossibleIndexTypes.addAll(ALL_STITCH_OUTPUT_TYPES); // Individual stitch types

        indexGroup.addArgument("-y", "--index-type")
                .choices(allPossibleIndexTypes.toArray(new String[0]))
                .setDefault(java.util.List.of("all"))
                .nargs("+")
                .help("Type of index to generate (can specify multiple, space-separated): " +
                      "unigram, bigram, trigram, dependency, ner_date, ner, pos, nash, " +
                      "various stitch_* types (e.g., stitch_unigram_date), " +
                      "'stitches' (for all stitch combinations), 'all' (for all available types).");

        indexGroup.addArgument("--custom-temp-dir")
                .dest("custom_temp_dir")
                .type(String.class)
                .required(false)
                .help("Path to a custom base directory for temporary files during index generation. If not specified, defaults to '<project_dir>/temp/'.");

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
        Path indexRootDirPath = Path.of(ns.getString("index_dir_path")).toAbsolutePath(); // directory containing multiple project index dirs

        // Derive project name from database file (remove extension)
        String projectName = dbFilePath.getFileName().toString().replaceFirst("\\.[^.]+$", "");

        // Each project gets its own directory directly under the root
        Path projectDirPath = indexRootDirPath.resolve(projectName);

        // For the rest of the pipeline code, this directory functions as the base index directory
        Path indexBasePath = projectDirPath;

        boolean force = ns.getBoolean("force");


        java.util.List<String> cliRequestedIndexTypes = ns.getList("index_type");

        logger.info("Starting Pipeline (DB: '{}', Index Dir: '{}', Stage: {}, CLI Requested Index Types: {})",
                    dbFilePath, indexRootDirPath, stage, cliRequestedIndexTypes);

        // --- Project Initialization & Validation ---

        // Validate database file existence
        if (!Files.exists(dbFilePath)) {
            logger.error("Database file not found: {}", dbFilePath.toAbsolutePath());
            throw new IOException("Database file not found: " + dbFilePath.toAbsolutePath());
        }
        logger.debug("Using database file: {}", dbFilePath.toAbsolutePath());

        // Create index directory and its 'indexes' subdirectory if they don't exist
        if (!Files.exists(indexRootDirPath)) {
            logger.info("Index directory '{}' does not exist. Creating...", indexRootDirPath.toAbsolutePath());
            Files.createDirectories(indexRootDirPath);
        } else {
            logger.debug("Using existing index directory '{}'", indexRootDirPath.toAbsolutePath());
        }

        if (!Files.exists(projectDirPath)) {
            logger.info("Project directory '{}' does not exist. Creating...", projectDirPath.toAbsolutePath());
            Files.createDirectories(projectDirPath);
        } else {
            logger.debug("Using existing project directory '{}'", projectDirPath.toAbsolutePath());
        }

        // --- Stage Execution ---

        // Run annotation stage if requested ('all' or 'annotate')
        if (stage.equals("all") || stage.equals("annotate")) {
            logger.info("--- Annotation Stage ---");
            logger.debug("About to run annotation on DB: {}", dbFilePath.toAbsolutePath());

            Annotations.runAnnotationStage(
                dbFilePath,
                ns.getInt("threads"),
                ns.getInt("batch_size"),
                ns.getInt("limit"),
                ns.getBoolean("force"),
                ns.getBoolean("fix_ids"),
                ns.get("cli_start_doc_id")
            );
            logger.info("Annotation stage completed.");
        }

        // Run indexing stage if requested ('all' or 'index')
        if (stage.equals("all") || stage.equals("index")) {
            logger.info("--- Indexing Stage ---");

            java.util.Set<String> baseIndexesPotentiallyNeeded = new java.util.LinkedHashSet<>();
            if (cliRequestedIndexTypes.contains("all") || cliRequestedIndexTypes.contains("stitches")) {
                baseIndexesPotentiallyNeeded.addAll(java.util.List.of(
                    "unigram", "bigram", "trigram", "ner", "ner_date", "pos"
                ));
            }
            cliRequestedIndexTypes.stream()
                .filter(type -> !type.equals("all") && !type.equals("stitches"))
                .forEach(baseIndexesPotentiallyNeeded::add);
            logger.debug("Base index types potentially relevant for the requested operations: {}", baseIndexesPotentiallyNeeded);

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

                 if (cliRequestedIndexTypes.contains("all")) {
                     logger.warn("Deleting all contents of index base directory due to --force and 'all' type: {}", indexBasePath.toAbsolutePath());
                     deleteDirectoryRecursively(indexBasePath);
                     Files.createDirectories(indexBasePath); // Recreate base directory
                     if (customTempDirArg == null || customTempDirArg.isBlank()) {
                        Path defaultTempDirForForceAll = indexBasePath.resolve("temp");
                        if (!Files.exists(defaultTempDirForForceAll)) {
                            Files.createDirectories(defaultTempDirForForceAll);
                            logger.info("Recreated default temporary directory after 'all' force delete: {}", defaultTempDirForForceAll.toAbsolutePath());
                        }
                     }
                 } else {
                     logger.info("Processing --force for specific index types: {}", cliRequestedIndexTypes);
                     java.util.Set<String> directoriesToClean = new java.util.LinkedHashSet<>();
                     boolean cleanTempStitchGen = false;

                     for (String requestedType : cliRequestedIndexTypes) {
                         String typeLower = requestedType.toLowerCase();
                         if (typeLower.equals("stitches")) {
                             directoriesToClean.addAll(ALL_STITCH_OUTPUT_TYPES);
                             cleanTempStitchGen = true; // Mark for specific deletion
                             logger.warn("--force specified for 'stitches'. Queuing all stitch output directories and '{}' for deletion.", TEMP_STITCH_GEN_DIR_NAME);
                         } else if (ALL_NON_STITCH_INDEX_TYPES.contains(typeLower) || ALL_STITCH_OUTPUT_TYPES.contains(typeLower)) {
                             directoriesToClean.add(typeLower);
                         } else if (!typeLower.equals("all")) { // "all" is handled above
                             logger.warn("Requested type '{}' for --force is not a known primary or stitch output type. It will be ignored for pipeline cleanup unless it's a custom index directory name.", requestedType);
                             directoriesToClean.add(typeLower);
                         }
                     }

                     for (String dirToCleanName : directoriesToClean) {
                         Path specificDirToClean = indexBasePath.resolve(dirToCleanName);
                         if (Files.exists(specificDirToClean)) {
                             logger.warn("Deleting existing index directory due to --force: {}", specificDirToClean.toAbsolutePath());
                             deleteDirectoryRecursively(specificDirToClean);
                         } else {
                             logger.info("Directory '{}' for type '{}' does not exist. No cleanup needed.", specificDirToClean.toAbsolutePath(), dirToCleanName);
                         }
                     }

                     if (cleanTempStitchGen) {
                         Path tempStitchGenPath = indexBasePath.resolve(TEMP_STITCH_GEN_DIR_NAME);
                         if (Files.exists(tempStitchGenPath)) {
                            logger.warn("Deleting '{}' directory due to --force on 'stitches': {}", TEMP_STITCH_GEN_DIR_NAME, tempStitchGenPath.toAbsolutePath());
                            deleteDirectoryRecursively(tempStitchGenPath);
                         } else {
                            logger.info("Directory '{}' does not exist. No cleanup needed.", tempStitchGenPath.toAbsolutePath());
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
                cliRequestedIndexTypes,
                effectiveCustomTempDirStr,
                force
            );
            logger.info("Indexing stage completed.");
        }

        // Write/update manifest for this project directory
        try {
            ProjectManifest.write(projectDirPath, dbFilePath);
            logger.info("Wrote project manifest to {}", projectDirPath.resolve(ProjectManifest.defaultFileName()).toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to write project manifest: {}", e.getMessage(), e);
        }

        logger.info("Pipeline completed successfully!");
    }

    private static void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
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
