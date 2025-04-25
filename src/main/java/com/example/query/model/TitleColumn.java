package com.example.query.model;

import com.example.core.IndexAccessInterface;
import com.example.query.binding.MatchDetail;
import com.example.query.sqlite.SqliteAccessor;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.columns.Column;

import java.util.Map;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a column that displays document titles.
 */
public class TitleColumn implements SelectColumn {
    private static final Logger logger = LoggerFactory.getLogger(TitleColumn.class);
    
    /**
     * Creates a new title column.
     */
    public TitleColumn() {
        // No configuration needed
    }
    
    @Override
    public String getColumnName() {
        return "title";
    }
    
    @Override
    public Column<?> createColumn() {
        return StringColumn.create(getColumnName());
    }
    
    @Override
    public void populateColumn(Table table, int rowIndex, List<?> detailsForUnit, 
                              String source,
                              Map<String, IndexAccessInterface> indexes) {
        StringColumn column = (StringColumn) table.column(getColumnName());
        if (detailsForUnit == null || detailsForUnit.isEmpty()) {
            logger.warn("Received empty detail list for row {} in TitleColumn.", rowIndex);
            column.setMissing(rowIndex);
            return;
        }
        // Cast to List<MatchDetail>
        @SuppressWarnings("unchecked")
        List<MatchDetail> matchDetails = (List<MatchDetail>) detailsForUnit;
        int documentId = matchDetails.get(0).getDocumentId();
        //logger.debug("Getting title for document {} from source {}", documentId, source);
        String value = SqliteAccessor.getInstance().getMetadata(source, documentId, "title");
        column.set(rowIndex, value != null ? value : "");
    }
    
    @Override
    public String toString() {
        return "TITLE";
    }
} 