package com.example.query;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.query.model.Query;
import com.example.query.parser.QueryLangLexer;
import com.example.query.parser.QueryLangParser;
import com.example.query.parser.QueryModelBuilder;

/**
 * Main entry point for parsing queries.
 * This class hides the ANTLR implementation details from the rest of the application.
 */
public class QueryParser {
    private static final Logger logger = LoggerFactory.getLogger(QueryParser.class);

    private static class ThrowingErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
            throw new RuntimeException("Syntax error at position " + charPositionInLine + ": " + msg);
        }
    }

    /**
     * Parses a query string and returns a Query object.
     *
     * @param queryString The query string to parse
     * @return The parsed Query object
     * @throws QueryParseException if there is an error parsing the query
     * @throws UnsupportedOperationException if a feature is not yet implemented
     */
    public Query parse(String queryString) throws QueryParseException {
        try {

            QueryLangLexer lexer = new QueryLangLexer(CharStreams.fromString(queryString));
            lexer.removeErrorListeners();
            lexer.addErrorListener(new ThrowingErrorListener());

            CommonTokenStream tokens = new CommonTokenStream(lexer);
            QueryLangParser parser = new QueryLangParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(new ThrowingErrorListener());

            ParseTree tree = parser.query();

            if (parser.getNumberOfSyntaxErrors() > 0) {
                throw new QueryParseException("Invalid query syntax");
            }

            QueryModelBuilder visitor = new QueryModelBuilder();
            Query query = visitor.buildQuery(tree);

            logger.debug("Successfully parsed query: {}", query);
            return query;
        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            logger.debug("Error parsing query: {}", queryString, e);
            String message = e.getMessage();
            if (message != null && message.startsWith("Failed to parse query: ")) {
                message = message.substring("Failed to parse query: ".length());
            }
            throw new QueryParseException(message, e);
        }
    }

    /**
     * Parses a subquery within a parent query.
     * This is used by the parser visitor when processing JOIN clauses.
     *
     * @param subqueryTree The parse tree for the subquery
     * @return The parsed Query object for the subquery
     */
    public Query parseSubquery(ParseTree subqueryTree) throws QueryParseException {
        try {
            QueryModelBuilder visitor = new QueryModelBuilder();
            Query subquery = visitor.buildSubquery(subqueryTree);

            logger.debug("Successfully parsed subquery: {}", subquery);
            return subquery;
        } catch (RuntimeException e) {
            logger.debug("Error parsing subquery", e);
            String message = e.getMessage();
            if (message != null && message.startsWith("Failed to parse subquery: ")) {
                message = message.substring("Failed to parse subquery: ".length());
            }
            throw new QueryParseException(message, e);
        }
    }
}