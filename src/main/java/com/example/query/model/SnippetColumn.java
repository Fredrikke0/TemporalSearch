package com.example.query.model;

import java.util.List;
import java.util.Map;

import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.query.executor.Bindings;
import com.example.query.executor.CellResult;
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
            throw new IllegalArgumentException(
                    "SnippetColumn requires a qualified variable name (e.g., alias.var), got: "
                            + qualifiedVariableName);
        }
        this.qualifiedVariableName = qualifiedVariableName;
        this.windowSize = windowSize >= 0 ? windowSize : DEFAULT_SNIPPET_WINDOW;
        this.columnName = "snippet_" + this.qualifiedVariableName.replace('.', '_');
    }

    public int getWindowSize() {
        return windowSize;
    }

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
            CellResult result, List<Integer> bindingIndices,
            String source,
            Map<String, IndexAccessInterface> indexes,
            Query query,
            Map<String, Object> contextCache) {
        StringColumn snippetColumn = table.stringColumn(this.columnName);

        if (bindingIndices == null || bindingIndices.isEmpty()) {
            logger.trace("No binding indices for snippet column '{}' at row {}. Setting missing.", columnName,
                    rowIndex);
            snippetColumn.setMissing(rowIndex);
            return;
        }

        Bindings bindings = result.bindings();
        if (bindings == null) {
            snippetColumn.setMissing(rowIndex);
            return;
        }

        // Find a matching binding
        boolean varFound = false;
        for (int bindingIdx : bindingIndices) {
            if (qualifiedVariableName.equals(bindings.variableNameAt(bindingIdx))) {
                varFound = true;
                break;
            }
        }

        if (!varFound) {
            snippetColumn.set(rowIndex,
                    "[Snippet N/A: Variable '" + qualifiedVariableName + "' not found.]");
            return;
        }

        // In the CellResult world, position-level data (begin/end chars) is not
        // available in bindings. We can extract document ID from cells.
        Roaring64NavigableMap cells = result.cells();
        int docId = -1;
        if (cells != null && !cells.isEmpty()) {
            long firstCell = cells.first();
            docId = PostingList.docIdFromCellKey(firstCell);
        }

        if (docId < 0) {
            snippetColumn.set(rowIndex, "[Snippet N/A: No cell data available.]");
            return;
        }

        // Get document text
        String docTextCacheKey = "docText_" + source + "_" + docId;
        String docText = (String) contextCache.get(docTextCacheKey);
        if (docText == null) {
            docText = SqliteAccessor.getInstance().getDocumentText(source, docId);
            if (docText != null) {
                contextCache.put(docTextCacheKey, docText);
            } else {
                logger.warn("Document text not found for docId {} in source {}. Cannot generate snippet.", docId,
                        source);
                snippetColumn.set(rowIndex, "[Error: Document text not available for snippet]");
                return;
            }
        }

        // Without precise begin/end chars, show start of document
        String snippet = generateSnippet(docText, 0, Math.min(100, docText.length()));
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
        textContent = textContent.replaceAll("\\R", " ");

        String prefix = (snippetStart > 0) ? "..." : "";
        String suffix = (snippetEnd < fullText.length()) ? "..." : "";

        return prefix + textContent.trim() + suffix;
    }

    @Override
    public String toString() {
        return "SNIPPET(" + qualifiedVariableName + ", WINDOW=" + windowSize + ")";
    }
}
