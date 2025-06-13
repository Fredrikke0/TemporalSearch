package com.example.query.model;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.query.executor.QueryResultSoA;
import com.example.query.sqlite.SqliteAccessor;

import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.columns.Column;

/**
 * Represents a snippet column in the SELECT clause.
 * Generates a text snippet centered around the value of a specified variable.
 */
public class SnippetColumn implements SelectColumn {
    private static final Logger logger = LoggerFactory.getLogger(SnippetColumn.class);

    private static final int DEFAULT_SNIPPET_WINDOW = 40;

    private final String columnName;
    private final int windowSize;
    private final String qualifiedVariableName;

    public SnippetColumn(String qualifiedVariableName, int windowSize) {
        if (qualifiedVariableName == null || qualifiedVariableName.isEmpty() || !qualifiedVariableName.contains(".")) {
            throw new IllegalArgumentException("SnippetColumn requires a qualified variable name (e.g., alias.var), got: " + qualifiedVariableName);
        }
        this.qualifiedVariableName = qualifiedVariableName;
        this.windowSize = windowSize >= 0 ? windowSize : DEFAULT_SNIPPET_WINDOW;
        this.columnName = "snippet_" + this.qualifiedVariableName.replace('.', '_');
    }

    public int getWindowSize() {
        return windowSize;
    }

    /**
     * Gets the qualified variable name this snippet is based on.
     */
    public String getVariableName() {
        return qualifiedVariableName;
    }

    @Override
    public String getColumnName() {
        return columnName;
    }

    @Override
    public Column<?> createColumn() {
        return StringColumn.create(columnName);
    }

    @Override
    public void populateColumn(Table table, int rowIndex,
                               QueryResultSoA resultSoA, List<Integer> indicesInSoA,
                               String source,
                               Map<String, IndexAccessInterface> indexes,
                               Query query,
                               Map<String, Object> contextCache) {
        StringColumn snippetColumn = table.stringColumn(this.columnName);

        if (indicesInSoA == null || indicesInSoA.isEmpty()) {
            logger.trace("No SoA indices for snippet column '{}' at row {}. Setting missing.", columnName, rowIndex);
            snippetColumn.setMissing(rowIndex);
            return;
        }

        Integer targetSoAIndex = null;
        for (int soaIndex : indicesInSoA) {
            if (qualifiedVariableName.equals(resultSoA.getVariableNameAt(soaIndex))) {
                if (resultSoA.getRequirements().needsPositions && resultSoA.getBeginCharAt(soaIndex) != -1) {
                    targetSoAIndex = soaIndex;
                    break;
                }
            }
        }

        if (targetSoAIndex == null) {
             logger.debug("No relevant SoA entry with position found for variable '{}' in this conceptual row. Snippet N/A.", qualifiedVariableName);
             // Provide more context why snippet is N/A if it's due to missing position for the variable.
             boolean varExistsWithoutPos = false;
             for (int soaIndex : indicesInSoA) {
                 if (qualifiedVariableName.equals(resultSoA.getVariableNameAt(soaIndex))) {
                     varExistsWithoutPos = true;
                     break;
                 }
             }
             if (varExistsWithoutPos) {
                snippetColumn.set(rowIndex, "[Snippet N/A: Variable '" + qualifiedVariableName + "' lacks position data in this context.]");
             } else {
                snippetColumn.set(rowIndex, "[Snippet N/A: Variable '" + qualifiedVariableName + "' not found or lacks position data.]");
             }
             return;
        }

        int docId = resultSoA.getDocumentIdAt(targetSoAIndex);
        int beginChar = resultSoA.getBeginCharAt(targetSoAIndex);
        int endChar = resultSoA.getEndCharAt(targetSoAIndex);

        // Get document text using contextCache if possible
        String docTextCacheKey = "docText_" + source + "_" + docId;
        String docText = (String) contextCache.get(docTextCacheKey);
        if (docText == null) {
            docText = SqliteAccessor.getInstance().getDocumentText(source, docId);
            if (docText != null) {
                contextCache.put(docTextCacheKey, docText);
            } else {
                 logger.warn("Document text not found for docId {} in source {}. Cannot generate snippet.", docId, source);
                 snippetColumn.set(rowIndex, "[Error: Document text not available for snippet]");
                 return;
            }
        }

        String snippet = generateSnippet(docText, beginChar, endChar);
        snippetColumn.set(rowIndex, snippet);
    }

    private String generateSnippet(String fullText, int matchStart, int matchEnd) {
        int snippetStart = Math.max(0, matchStart - this.windowSize);
        int snippetEnd = Math.min(fullText.length(), matchEnd + this.windowSize);

        if (snippetStart >= snippetEnd) {
            snippetStart = Math.max(0, matchStart - 5);
            snippetEnd = Math.min(fullText.length(), matchEnd + 5);

            if (snippetStart >= snippetEnd) {
               snippetStart = Math.max(0, matchStart);
               snippetEnd = Math.min(fullText.length(), matchEnd);
            }
        }

        String textContent = fullText.substring(snippetStart, snippetEnd);

        String prefix = (snippetStart > 0) ? "..." : "";
        String suffix = (snippetEnd < fullText.length()) ? "..." : "";

        return prefix + textContent.trim() + suffix;
    }

    @Override
    public String toString() {
        return "SNIPPET(" + qualifiedVariableName + ", WINDOW=" + windowSize + ")";
    }
}