package com.baskettecase.gpmcp.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.List;

/**
 * Utility class for formatting MCP tool responses as JSON for table rendering in gp-assistant frontend.
 *
 * The gp-assistant frontend automatically detects JSON arrays in responses and renders them as styled tables.
 * This formatter ensures consistent JSON formatting across all MCP tools.
 *
 * @see <a href="/Users/dbbaskette/Projects/gp-assistant/MCP_SERVER_JSON_RESPONSE_GUIDE.md">MCP Server JSON Response Guide</a>
 */
public class JsonResponseFormatter {

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Format a list of objects as a JSON array wrapped in a markdown code block
     *
     * @param data List of objects to format
     * @return Formatted string with JSON wrapped in ```json code block
     * @throws JsonProcessingException if serialization fails
     */
    public static String formatAsJsonTable(List<?> data) throws JsonProcessingException {
        if (data == null || data.isEmpty()) {
            return "";
        }

        String json = mapper.writeValueAsString(data);
        return "```json\n" + json + "\n```";
    }

    /**
     * Format a list with a descriptive message
     *
     * @param message Descriptive message to prepend
     * @param data List of objects to format
     * @return Formatted string with message and JSON table
     * @throws JsonProcessingException if serialization fails
     */
    public static String formatWithMessage(String message, List<?> data) throws JsonProcessingException {
        if (data == null || data.isEmpty()) {
            return message + "\n\nNo data found.";
        }

        return message + "\n\n" + formatAsJsonTable(data);
    }

    /**
     * Format a list with row count information
     *
     * @param rowCount Number of rows in the result
     * @param data List of objects to format
     * @return Formatted string with row count and JSON table
     * @throws JsonProcessingException if serialization fails
     */
    public static String formatWithRowCount(int rowCount, List<?> data) throws JsonProcessingException {
        if (data == null || data.isEmpty()) {
            return "Query returned 0 rows.";
        }

        String plural = rowCount == 1 ? "row" : "rows";
        return String.format("Query returned %d %s:\n\n%s", rowCount, plural, formatAsJsonTable(data));
    }
}
