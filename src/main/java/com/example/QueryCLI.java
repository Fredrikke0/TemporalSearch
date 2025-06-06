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
import com.example.query.executor.JoinOptimizationStrategy;
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
    private final JoinOptimizationStrategy joinOptimizationStrategy; // Stored from constructor
    private final String stitchStrategyName; // Stored from constructor

    /**
     * Creates a new QueryCLI instance.
     *
     * @param dbFilePath The path to the project's SQLite database file.
     * @param indexDirPath The path to the directory containing project indexes.
     * @param temporalStrategyName The desired temporal execution strategy ("nash" or "naive")
     * @param joinOptStrategy The desired join execution strategy
     * @param stitchStrategyName The desired stitch execution strategy ("none" or "optimized")
     */
    public QueryCLI(String dbFilePath, Path indexDirPath, String temporalStrategyName, JoinOptimizationStrategy joinOptStrategy, String stitchStrategyName) {
        this.dbFilePath = dbFilePath;
        this.indexDirPath = indexDirPath;
        this.parser = new QueryParser();
        this.validator = new QuerySemanticValidator();

        this.temporalStrategyName = temporalStrategyName;
        this.joinOptimizationStrategy = joinOptStrategy;
        this.stitchStrategyName = stitchStrategyName;

        logger.info("Initialized QueryCLI with DB file: {} and Index directory: {}", dbFilePath, indexDirPath);
        logger.info("Temporal Strategy: {}, Join Strategy: {}, Stitch Strategy: {}", temporalStrategyName, joinOptStrategy, stitchStrategyName);
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
        try {
            logger.debug("Parsing query: {}", queryStr);
            Query query = parser.parse(queryStr);

            logger.debug("Validating query: {}", query);
            validator.validate(query);

            checkAndDisplayDateQueryHelp(queryStr, query);

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
                ConditionExecutorFactory executorFactory = new ConditionExecutorFactory(synonymManager);
                executorFactory.setTemporalStrategy(this.temporalStrategyName); // Set strategy on the factory

                QueryExecutor queryExecutor = new QueryExecutor(executorFactory, this.stitchStrategyName, synonymManager);
                queryExecutor.setJoinOptimizationStrategy(this.joinOptimizationStrategy); // Set strategy on the executor

                logger.debug("Executing query against project: {}", projectName);
                Query.Granularity granularity = query.granularity();
                int windowSize = query.granularitySize().orElse(0);
                logger.info("Query granularity: {} with size: {}", granularity, windowSize);

                QueryResultSoA execResult = queryExecutor.execute(query, indexManager); // Pass IndexManager

                Table resultTable;
                int matchCount;
                String matchUnit;

                if (execResult != null) {
                    matchCount = execResult.size();
                    if (query.joinCondition().isPresent()) {
                        matchUnit = "conceptual joined rows";
                    } else {
                        matchUnit = (granularity == Query.Granularity.DOCUMENT) ? "documents" : "sentences";
                    }
                    logger.info("Query executed, found {} matching {} (granularity for base parts: {})", matchCount, matchUnit, granularity);

                    logger.debug("Generating result table from QueryResultSoA");
                    Map<String, IndexAccessInterface> allIndexes = indexManager.getAllIndexes(); // Get the map here
                    resultTable = tableResultService.generateTable(
                        query,
                        execResult,
                        allIndexes // Pass the map
                    );
                } else {
                    logger.error("Query execution returned a null QueryResultSoA.");
                    System.err.println("Error: Query execution resulted in an unexpected null result.");
                    // Create an empty table or handle error appropriately
                    resultTable = Table.create("Empty Result"); // Placeholder for an empty table
                    matchCount = 0;
                    matchUnit = "results";
                }

                // 6. Generate results using TableResultService (removed from inside if/else)
                // The specific generation logic is now handled within the if/else branches above

                // NOTE: We're now using Tablesaw's sorting capabilities directly
                // The orderBy list in Query now contains Tablesaw-compatible sort strings
                // (column names with optional "-" prefix for descending order)

                // 7. Handle export if requested
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
                    // 8. Format and display results
                    logger.debug("Formatting results for display");
                    String formattedResults = tableResultService.formatTable(resultTable);

                    // Output the formatted results
                    System.out.println(formattedResults);
                }
            }

        } catch (QueryParseException e) {
            logger.error("Query parse error: {}", e.getMessage());
            System.err.println("Error parsing query: " + e.getMessage());
            // No position information in QueryParseException
        } catch (Exception e) {
            logger.error("Error executing query: {}", e.getMessage(), e);
            System.err.println("Error: " + e.getMessage());
        } finally {
            long endTimeNs = System.nanoTime();
            double executionTimeMs = (endTimeNs - startTimeNs) / 1_000_000.0;
            System.out.printf("BENCHMARK_EXECUTION_TIME_MS: %.3f%n", executionTimeMs);
        }
    }

    /**
     * Checks if the query involves date operations and displays helpful information.
     *
     * @param queryStr The original query string
     * @param query The parsed query object
     */
    private void checkAndDisplayDateQueryHelp(String queryStr, Query query) {
        if (queryStr.toUpperCase().contains("DATE(")) {
            // Check for specific predicates
            if (queryStr.toUpperCase().contains("DATE(CONTAINS [")) {
                logger.info("Date CONTAINS query detected - requires dates to be fully within range");
                System.out.println("\nQuery Help: You're using DATE(CONTAINS [range]) which requires dates to be fully within the specified range.");
                System.out.println("For broader matches, consider using DATE(INTERSECT [range]) which matches any overlap with the range.\n");
            } else if (queryStr.toUpperCase().contains("DATE(INTERSECT [")) {
                logger.info("Date INTERSECT query detected - matches any overlap with date range");
            }

            // Check for granularity
            if (query.granularity() == Query.Granularity.SENTENCE) {
                logger.info("Date query with sentence granularity detected");
                System.out.println("Note: With sentence granularity, the query will return specific sentences containing date mentions in the specified range.");
            } else {
                logger.info("Date query with document granularity detected");
                System.out.println("Note: With document granularity, the query will return documents containing date mentions in the specified range.");
            }
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

        // Add the new temporal strategy flag
        parser.addArgument("--temporal-strategy")
                .choices("nash", "naive") // Define allowed choices
                .setDefault("naive")      // Set the default value
                .help("Select the execution strategy for temporal conditions (default: naive)");

        // Add the new join strategy flag
        parser.addArgument("--join-strategy")
                .choices("independent", "dependent")
                .setDefault("independent")
                .help("Specifies the execution strategy for JOIN operations. 'independent' executes both sides fully before joining (default). 'dependent' attempts to optimize by filtering one side based on the results of the other.");

        // Add the new stitch strategy flag
        parser.addArgument("--stitch-strategy")
                .choices("none", "optimized") // Define allowed choices
                .setDefault("none")      // Set the default value
                .help("Select the execution strategy for stitch index optimization (default: none)");

        parser.addArgument("query")
                .nargs("?")
                .help("Query string to execute");

        try {
            // Parse arguments
            Namespace ns = parser.parseArgs(args);
            String dbFileStr = ns.getString("db_file");
            Path indexDirPath = Path.of(ns.getString("index_dir"));
            String query = ns.getString("query");
            String exportArg = ns.getString("export");
            String temporalStrategy = ns.getString("temporal_strategy"); // Get the strategy name
            String joinStrategyStr = ns.getString("join_strategy");
            String stitchStrategy = ns.getString("stitch_strategy"); // Get stitch strategy
            JoinOptimizationStrategy joinStrategyEnum;
            if ("dependent".equalsIgnoreCase(joinStrategyStr)) {
                joinStrategyEnum = JoinOptimizationStrategy.DEPENDENT;
            } else {
                joinStrategyEnum = JoinOptimizationStrategy.INDEPENDENT;
            }

            // Parse export argument if provided
            Optional<String> exportFormat = Optional.empty();
            Optional<String> exportFilename = Optional.empty();

            if (exportArg != null && !exportArg.isEmpty()) {
                String[] parts = exportArg.split(":", 2);
                if (parts.length == 2) {
                    exportFormat = Optional.of(parts[0]);
                    exportFilename = Optional.of(parts[1]);
                } else {
                    System.err.println("Invalid export format. Use format:filename (e.g., csv:results.csv)");
                    System.exit(1);
                }
            }

            // Create and run CLI, passing the chosen strategies
            logger.info("Configuring temporal strategy: {}", temporalStrategy);
            logger.info("Configuring join strategy: {}", joinStrategyEnum);
            logger.info("Configuring stitch strategy: {}", stitchStrategy);
            QueryCLI cli = new QueryCLI(dbFileStr, indexDirPath, temporalStrategy, joinStrategyEnum, stitchStrategy);

            if (query != null) {
                // Execute the provided query
                cli.executeQuery(query, exportFormat, exportFilename);
            } else {
                // Interactive mode
                Scanner scanner = new Scanner(System.in);
                System.out.println("Query CLI - Enter queries or 'exit' to quit");
                System.out.println("Using DB file: " + dbFileStr);
                System.out.println("Using Index directory: " + indexDirPath.toString());
                System.out.println("Specify project in query using: FROM [PROJECT_NAME]");
                System.out.println("Temporal Strategy: " + temporalStrategy + " (Use --temporal-strategy nash|naive to change at startup)");
                System.out.println("Join Strategy: " + joinStrategyEnum.name().toLowerCase() + " (Use --join-strategy independent|dependent to change at startup)");
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
                        // Note: In interactive mode, the strategy chosen at startup is used for all queries.
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