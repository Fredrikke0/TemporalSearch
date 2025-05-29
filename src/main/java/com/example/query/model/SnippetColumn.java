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

    private static final int DEFAULT_SNIPPET_WINDOW = 5; // Default words before/after

    private final String columnName;
    private final int windowSize;
    private final String qualifiedVariableName; // Store qualified name (e.g., "$main.term" or "q1.term")

    public SnippetColumn(String qualifiedVariableName, int windowSize) {
        if (qualifiedVariableName == null || qualifiedVariableName.isEmpty() || !qualifiedVariableName.contains(".")) {
            throw new IllegalArgumentException("SnippetColumn requires a qualified variable name (e.g., alias.var), got: " + qualifiedVariableName);
        }
        this.qualifiedVariableName = qualifiedVariableName;
        this.windowSize = windowSize >= 0 ? windowSize : DEFAULT_SNIPPET_WINDOW; // Allow window 0
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
        int snippetStart = findWordBoundary(fullText, matchStart, -windowSize);
        int snippetEnd = findWordBoundary(fullText, matchEnd, windowSize);

        // Ensure snippetStart and snippetEnd are within fullText bounds and in correct order
        snippetStart = Math.max(0, snippetStart);
        snippetEnd = Math.min(fullText.length(), snippetEnd);
        if (snippetStart >= snippetEnd) { // Should not happen if findWordBoundary is correct
             logger.warn("Calculated snippet boundaries are invalid: start={}, end={}. Using match boundaries.", snippetStart, snippetEnd);
             snippetStart = Math.max(0, matchStart);
             snippetEnd = Math.min(fullText.length(), matchEnd);
        }

        String snippet = fullText.substring(snippetStart, snippetEnd);

        return "..." + snippet.trim() + "...";
    }

    private int findWordBoundary(String text, int center, int wordOffset) {
        int currentPos = center;
        int wordsFound = 0;
        int direction = wordOffset > 0 ? 1 : -1;

        // Handle edge case: if center is already at the beginning/end and we want to go further out.
        if (direction == -1 && center == 0 && wordOffset < 0) return 0;
        if (direction == 1 && center == text.length() && wordOffset > 0) return text.length();

        while (wordsFound < Math.abs(wordOffset) && currentPos >= 0 && currentPos < text.length()) {
            int prevCharPos = currentPos - direction; // Look at the character on the "other side" of currentPos for transition

            if (prevCharPos >= 0 && prevCharPos < text.length()) {
                boolean currentIsSpace = Character.isWhitespace(text.charAt(currentPos));
                boolean prevIsSpace = Character.isWhitespace(text.charAt(prevCharPos));
                if (direction == 1) { // Moving right (finding end of snippet)
                    if (prevIsSpace && !currentIsSpace) wordsFound++; // Space to Non-space transition marks start of a new word
                } else { // Moving left (finding start of snippet)
                    if (!prevIsSpace && currentIsSpace) wordsFound++; // Non-space to Space transition marks end of a word going left
                }
            }

            if (wordsFound >= Math.abs(wordOffset)) break;

            currentPos += direction;
            if (currentPos < 0 || currentPos >= text.length()) break;
        }

        // Adjust to be just after/before the space boundary, or at text ends
        if (direction == 1) { // Moving right, ensure we are at the start of the word or text end
            while (currentPos < text.length() && Character.isWhitespace(text.charAt(currentPos))) {
                currentPos++;
            }
        } else { // Moving left, ensure we are at the end of the word or text start
             while (currentPos > 0 && Character.isWhitespace(text.charAt(currentPos -1))) {
                currentPos--;
            }
        }

        return Math.max(0, Math.min(text.length(), currentPos));
    }

    @Override
    public String toString() {
        return "SNIPPET(" + qualifiedVariableName + ", WINDOW=" + windowSize + ")";
    }
}