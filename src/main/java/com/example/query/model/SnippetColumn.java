package com.example.query.model;

import com.example.core.IndexAccessInterface;
import com.example.query.binding.MatchDetail;
import com.example.query.executor.NerExecutor;
import com.example.query.sqlite.SqliteAccessor;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.columns.Column;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.Position;

/**
 * Represents a snippet column in the SELECT clause.
 * Generates a text snippet centered around the value of a specified variable.
 */
public class SnippetColumn implements SelectColumn {
    private static final Logger logger = LoggerFactory.getLogger(SnippetColumn.class);
    
    private static final int DEFAULT_SNIPPET_WINDOW = 5; // Default words before/after

    private final String columnName; // Changed to instance variable
    private final int windowSize;
    private final String variableName; // Stored variable name including ? prefix

    public SnippetColumn(String variableNameWithPrefix, int windowSize) {
        if (variableNameWithPrefix == null || variableNameWithPrefix.isEmpty() || !variableNameWithPrefix.startsWith("?")) {
            throw new IllegalArgumentException("Variable name must not be null, empty, and must start with '?' for SnippetColumn");
        }
        this.variableName = variableNameWithPrefix; // Store variable name (e.g., "?term")
        this.windowSize = windowSize >= 0 ? windowSize : DEFAULT_SNIPPET_WINDOW; // Allow window 0
        this.columnName = "snippet " + this.variableName.substring(1);
    }

    public int getWindowSize() {
        return windowSize;
    }
    
    public String getVariableName() {
        return variableName;
    }
    
    @Override
    public String getColumnName() {
        return columnName; // Return dynamic name
    }
    
    @Override
    public Column<?> createColumn() {
        return StringColumn.create(columnName); // Use dynamic name
    }
    
    @Override
    public void populateColumn(Table table, int rowIndex, List<?> detailsForUnit, 
                               String source,
                               Map<String, IndexAccessInterface> indexes) {
        StringColumn snippetColumn = table.stringColumn(this.columnName);
        
        // 1. Find the first MatchDetail in the list that matches our variable name (including '?')
        Optional<MatchDetail> relevantDetailOpt = detailsForUnit.stream()
            .filter(MatchDetail.class::isInstance)
            .map(MatchDetail.class::cast)
            .filter(d -> variableName.equals(d.variableName()))
            .findFirst();
            
        if (relevantDetailOpt.isEmpty()) {
             snippetColumn.setMissing(rowIndex);
             return;
        }
        
        MatchDetail relevantDetail = relevantDetailOpt.get();
        
        // 2. Check if the found detail has a valid position
        if (relevantDetail.position() == null || relevantDetail.position().getBeginPosition() == -1) {
            logger.debug("Relevant MatchDetail for variable '{}' lacks valid position. Cannot generate snippet.", variableName);
            snippetColumn.set(rowIndex, "[Snippet N/A: Match lacks position. Use DATE(?d) etc. for context.]");
            return;
        }

        // 3. Get document text
        String docText = SqliteAccessor.getInstance().getDocumentText(source, relevantDetail.getDocumentId());
        
        if (docText == null) {
            logger.warn("Document text not found for docId {} in source {}. Cannot generate snippet.", relevantDetail.getDocumentId(), source);
            snippetColumn.set(rowIndex, "[Error: Document text not available]");
            return;
        }
        
        // 4. Generate snippet
        String snippet = generateSnippet(docText, relevantDetail.position());
        snippetColumn.set(rowIndex, snippet);
    }

    private String generateSnippet(String fullText, Position matchPosition) {
        // Simplified snippet generation - find words around the match
        // A real implementation would be more robust (handle boundaries, punctuation, etc.)
        int start = matchPosition.getBeginPosition();
        int end = matchPosition.getEndPosition();
        
        // Find word boundaries around the start/end offsets based on windowSize
        int snippetStart = findWordBoundary(fullText, start, -windowSize);
        int snippetEnd = findWordBoundary(fullText, end, windowSize);
        
        String snippet = fullText.substring(snippetStart, snippetEnd);
        
        // Maybe add emphasis? e.g., ...word [*match*] word...
        // String matchedText = fullText.substring(start, end);
        // snippet = snippet.replace(matchedText, "[*" + matchedText + "*]"); 

        return "..." + snippet.trim() + "..."; // Add ellipsis
    }

    // Helper to find approximate word boundary
    private int findWordBoundary(String text, int center, int wordOffset) {
        int currentPos = center;
        int wordsFound = 0;
        int direction = wordOffset > 0 ? 1 : -1;

        while (wordsFound < Math.abs(wordOffset)) {
            int nextPos = currentPos + direction;
            if (nextPos < 0 || nextPos >= text.length()) {
                break; // Reached text boundary
            }
            
            // Check for space -> non-space transition (start/end of word)
            if (Character.isWhitespace(text.charAt(currentPos)) && !Character.isWhitespace(text.charAt(nextPos)) && direction > 0) {
                 wordsFound++;
            } else if (!Character.isWhitespace(text.charAt(currentPos)) && Character.isWhitespace(text.charAt(nextPos)) && direction < 0) {
                 wordsFound++;
            }
            
            currentPos = nextPos;
            // Safety break for very long non-whitespace sequences
            if (Math.abs(currentPos - center) > 200) break; 
        }
        
        // Adjust to be just after/before the space boundary
        while (currentPos > 0 && currentPos < text.length() -1 && Character.isWhitespace(text.charAt(currentPos))) {
            currentPos += direction;
        }
        
        return Math.max(0, Math.min(text.length(), currentPos));
    }
    
    @Override
    public String toString() {
        return "SNIPPET(" + variableName + ")"; // Use full variable name
    }
} 