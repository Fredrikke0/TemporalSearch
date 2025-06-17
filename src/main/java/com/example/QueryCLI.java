package com.example;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.index.util.SynonymManager;
import com.example.query.QueryParseException;
import com.example.query.QueryParser;
import com.example.query.QuerySemanticValidator;
import com.example.query.executor.ConditionExecutorFactory;
import com.example.query.executor.PushdownStrategy;
import com.example.query.executor.QueryExecutor;
import com.example.query.executor.QueryResultSoA;
import com.example.query.index.IndexManager;
import com.example.query.model.Query;
import com.example.query.result.TableResultService;
import com.example.query.sqlite.SqliteAccessor;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import tech.tablesaw.api.Table;

/**
 * Command-line interface for executing queries against the indexed corpus.
 * Serves as the entry point and orchestrator for the query engine.
 */
public class QueryCLI {
    private static final Logger logger = LoggerFactory.getLogger(QueryCLI.class);
    private final String dbFilePath;
    private final Path indexDirPath;

    // Core components
    private final QueryParser parser;
    private final QuerySemanticValidator validator;
    // executorFactory and executor are now created per-query execution

    private final String temporalStrategyName; // Stored from constructor
    private final PushdownStrategy pushdownStrategy; // ADDED
    private final String stitchStrategyName; // Stored from constructor

    /**
     * Creates a new QueryCLI instance.
     *
     * @param dbFilePath The path to the project's SQLite database file.
     * @param indexDirPath The path to the directory containing project indexes.
     * @param temporalStrategyName The desired temporal execution strategy ("nash" or "naive")
     * @param pushdownStrategy The desired pushdown strategy
     * @param stitchStrategyName The desired stitch execution strategy ("none" or "optimized")
     */
    public QueryCLI(String dbFilePath, Path indexDirPath, String temporalStrategyName, PushdownStrategy pushdownStrategy, String stitchStrategyName) {
        this.dbFilePath = dbFilePath;
        this.indexDirPath = indexDirPath;
        this.parser = new QueryParser();
        this.validator = new QuerySemanticValidator();

        this.temporalStrategyName = temporalStrategyName;
        this.pushdownStrategy = pushdownStrategy;
        this.stitchStrategyName = stitchStrategyName;

        logger.info("Initialized QueryCLI with DB file: {} and Index directory: {}", dbFilePath, indexDirPath);
        logger.info("Temporal Strategy: {}, Pushdown Strategy: {}, Stitch Strategy: {}", temporalStrategyName, pushdownStrategy, stitchStrategyName);
    }

    /**
     * Executes a query string.
     *
     * @param queryStr The query string to execute
     * @param exportFormat Optional export format (csv, json, html)
     * @param exportFilename Optional export filename
     */
    public void executeQuery(String queryStr, Optional<String> exportFormat, Optional<String> exportFilename) {
        long startTimeNs = System.nanoTime(); // Start timing
        double coreProcessingTimeMs = -1.0; // Initialize to indicate not set
        long coreProcessingStartTimeNs = 0; // Initialize

        try {
            logger.debug("Parsing query: {}", queryStr);
            Query query = parser.parse(queryStr);

            logger.debug("Validating query: {}", query);
            validator.validate(query, Optional.empty());

            String projectName = query.source();
            logger.debug("Using project name from FROM clause: {}", projectName);
            logger.debug("Using database file: {}", this.dbFilePath);
            logger.debug("Using index base directory: {}", this.indexDirPath);

            if (!new java.io.File(this.dbFilePath).exists()) {
                String errorMessage = String.format("Database file not found: %s.", this.dbFilePath);
                logger.error(errorMessage);
                System.err.println("Error: " + errorMessage);
                System.err.println("Ensure the specified database file exists.");
                return;
            }

            SqliteAccessor.initialize(this.dbFilePath);
            logger.debug("Initialized SqliteAccessor for database: {}", this.dbFilePath);
            logger.debug("Initializing Nash temporal index (if applicable) for project: {}", projectName);

            TableResultService tableResultService = new TableResultService(this.dbFilePath);
            logger.info("Using database at: {}", this.dbFilePath);

            try (IndexManager indexManager = new IndexManager(this.indexDirPath, projectName, query, this.temporalStrategyName, this.stitchStrategyName)) {
                logger.debug("Created IndexManager for project: {} using index directory: {}", projectName, this.indexDirPath);

                SynonymManager synonymManager = indexManager.getSynonymManager();
                Query.Granularity queryGranularity = query.granularity(); // Get granularity from the query

                // Add warning for stitch strategy and granularity mismatch
                if ("optimized".equalsIgnoreCase(this.stitchStrategyName) && queryGranularity != Query.Granularity.SENTENCE) {
                    String warningMessage = "Warning: Stitch optimization ('optimized') is active, but it is primarily designed and most effective for SENTENCE granularity. Current query granularity is " + queryGranularity + ". Results might not be optimal or behave as expected with stitching.";
                    logger.warn(warningMessage);
                    System.err.println(warningMessage);
                }

                // Create and configure the ConditionExecutorFactory
                ConditionExecutorFactory factory = new ConditionExecutorFactory(synonymManager, this.stitchStrategyName, queryGranularity);
                factory.setTemporalStrategy(this.temporalStrategyName); // Configure with the desired temporal strategy
                logger.debug("ConditionExecutorFactory created and configured with temporal strategy: {}, stitch strategy: {}, query granularity: {}", this.temporalStrategyName, this.stitchStrategyName, queryGranularity);

                // Inject the configured factory into QueryExecutor
                QueryExecutor queryExecutor = new QueryExecutor(tableResultService, this.stitchStrategyName, synonymManager, factory);
                queryExecutor.setPushdownStrategy(this.pushdownStrategy);

                logger.debug("Executing query against project: {}", projectName);
                int windowSize = query.granularitySize().orElse(0);
                logger.info("Query granularity: {} with size: {}", queryGranularity, windowSize);

                coreProcessingStartTimeNs = System.nanoTime(); // Start core processing timer
                QueryResultSoA execResult = queryExecutor.execute(query, indexManager); // Pass IndexManager

                Table resultTable;
                int matchCount;
                String matchUnit;

                if (execResult != null) {
                    matchCount = execResult.size();
                    if (!query.joinSteps().isEmpty()) {
                        matchUnit = "conceptual joined rows";
                    } else {
                        matchUnit = (queryGranularity == Query.Granularity.DOCUMENT) ? "documents" : "sentences";
                    }
                    logger.info("Query executed, found {} matching {} (granularity for base parts: {})", matchCount, matchUnit, queryGranularity);

                    Map<String, IndexAccessInterface> allIndexes = indexManager.getAllIndexes();
                    resultTable = tableResultService.generateTable(
                        query,
                        execResult,
                        allIndexes
                    );
                } else {
                    logger.error("Query execution returned a null QueryResultSoA.");
                    System.err.println("Error: Query execution resulted in an unexpected null result.");
                    resultTable = Table.create("Empty Result");
                    matchCount = 0;
                    matchUnit = "results";
                }

                if (exportFormat.isPresent() && exportFilename.isPresent()) {
                    String format = exportFormat.get();
                    String filename = exportFilename.get();
                    logger.info("Exporting results to {} format in file: {}", format, filename);

                    try {
                        tableResultService.exportTable(resultTable, format, filename);
                        System.out.println("Results exported to " + filename);
                    } catch (IOException e) {
                        logger.error("Error exporting results: {}", e.getMessage());
                        System.err.println("Error exporting results: " + e.getMessage());
                    }
                } else {
                    logger.debug("Formatting results for display");
                    String formattedResults = tableResultService.formatTable(resultTable);

                    System.out.println(formattedResults);
                }
                long coreProcessingEndTimeNs = System.nanoTime();
                coreProcessingTimeMs = (coreProcessingEndTimeNs - coreProcessingStartTimeNs) / 1_000_000.0;
            }

        } catch (QueryParseException e) {
            logger.error("Query parse error: {}", e.getMessage());
            System.err.println("Error parsing query: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error executing query: {}", e.getMessage(), e);
            System.err.println("Error: " + e.getMessage());
        } finally {
            long endTimeNs = System.nanoTime();
            double executionTimeMs = (endTimeNs - startTimeNs) / 1_000_000.0;
            if (coreProcessingTimeMs >= 0) {
                System.out.printf("BENCHMARK_CORE_PROCESSING_TIME_MS: %.3f%n", coreProcessingTimeMs);
            }
            System.out.printf("BENCHMARK_EXECUTION_TIME_MS: %.3f%n", executionTimeMs);
        }
    }

    /**
     * Main entry point for the CLI.
     *
     * @param args Command-line arguments
     */
    public static void main(String[] args) {
        // Set up argument parser
        ArgumentParser parser = ArgumentParsers.newFor("QueryCLI").build()
                .defaultHelp(true)
                .description("Execute queries against indexed projects. Queries specify the project via the FROM clause.");

        parser.addArgument("--db-file")
                .required(true)
                .help("Path to the project's SQLite database file.");

        parser.addArgument("--index-dir")
                .required(true)
                .help("Path to the directory containing project indexes.");

        parser.addArgument("--export")
                .help("Export results to a file in the specified format: csv:filename.csv, json:filename.json, or html:filename.html");

        parser.addArgument("--temporal-strategy")
                .choices("nash", "naive").setDefault("naive")
                .help("Specify the temporal execution strategy (nash or naive).");

        parser.addArgument("--pushdown-strategy")
                .choices("none", "optimized").setDefault("none")
                .type(String.class)
                .help("Specify the predicate pushdown strategy (none or optimized). Default: optimized.");

        parser.addArgument("--stitch-strategy")
                .choices("none", "optimized").setDefault("none")
                .help("Specify the stitch execution strategy (none or optimized).");

        parser.addArgument("query")
                .nargs("?")
                .help("The query string to execute. If not provided, enters interactive mode.");

        try {
            Namespace ns = parser.parseArgs(args);
            String dbFile = ns.getString("db_file");
            String indexDir = ns.getString("index_dir");
            String exportArg = ns.getString("export");
            String temporalStrategy = ns.getString("temporal_strategy");
            String pushdownStrategyStr = ns.getString("pushdown_strategy");
            PushdownStrategy pushdownStrategy = PushdownStrategy.fromString(pushdownStrategyStr);
            String stitchStrategy = ns.getString("stitch_strategy");
            String queryStr = ns.getString("query");

            Optional<String> exportFormat = Optional.empty();
            Optional<String> exportFilename = Optional.empty();

            if (exportArg != null) {
                String[] parts = exportArg.split(":", 2);
                if (parts.length == 2) {
                    exportFormat = Optional.of(parts[0]);
                    exportFilename = Optional.of(parts[1]);
                } else {
                    System.err.println("Invalid export format. Use format:filename (e.g., csv:output.csv)");
                    return;
                }
            }

            QueryCLI cli = new QueryCLI(dbFile, Path.of(indexDir), temporalStrategy, pushdownStrategy, stitchStrategy);

            if (queryStr != null) {
                cli.executeQuery(queryStr, exportFormat, exportFilename);
            } else {
                Scanner scanner = new Scanner(System.in);
                System.out.println("Query CLI - Enter queries or 'exit' to quit");
                System.out.println("Using DB file: " + dbFile);
                System.out.println("Using Index directory: " + indexDir);
                System.out.println("Specify project in query using: FROM [PROJECT_NAME]");
                System.out.println("Temporal Strategy: " + temporalStrategy + " (Use --temporal-strategy nash|naive to change at startup)");
                System.out.println("Pushdown Strategy: " + pushdownStrategy.name().toLowerCase() + " (Use --pushdown-strategy none|optimized to change at startup)");
                System.out.println("Stitch Strategy: " + stitchStrategy + " (Use --stitch-strategy none|optimized to change at startup)");
                System.out.println("Snippet support is enabled. Use SNIPPET(variable) in SELECT clause to show text context.");
                System.out.println("Export support: Add --export=format:filename to export results (formats: csv, json, html)");

                while (true) {
                    System.out.print("\nQuery> ");
                    String input = scanner.nextLine().trim();

                    if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                        break;
                    }

                    if (!input.isEmpty()) {
                        cli.executeQuery(input, exportFormat, exportFilename);
                    }
                }

                scanner.close();
            }

        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        }
    }
}