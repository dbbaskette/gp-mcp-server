package com.baskettecase.gpmcp.util;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Fuzzy Matcher for finding closest matches to misspelled database objects
 *
 * Uses Levenshtein distance algorithm to find "did you mean?" suggestions
 * for table names, column names, and schema names.
 */
@Slf4j
public class FuzzyMatcher {

    private static final int MAX_SUGGESTIONS = 3;
    private static final double SIMILARITY_THRESHOLD = 0.4; // 40% similarity required

    /**
     * Find the closest match to the input string from a list of candidates
     *
     * @param input The misspelled string
     * @param candidates List of valid strings to match against
     * @return The closest match, or null if no good match found
     */
    public static String findClosestMatch(String input, List<String> candidates) {
        if (input == null || input.isEmpty() || candidates == null || candidates.isEmpty()) {
            return null;
        }

        String normalizedInput = input.toLowerCase();
        String bestMatch = null;
        double bestSimilarity = 0.0;

        for (String candidate : candidates) {
            String normalizedCandidate = candidate.toLowerCase();
            double similarity = calculateSimilarity(normalizedInput, normalizedCandidate);

            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestMatch = candidate;
            }
        }

        // Only return match if similarity is above threshold
        if (bestSimilarity >= SIMILARITY_THRESHOLD) {
            log.debug("Fuzzy match: '{}' -> '{}' (similarity: {:.2f})", input, bestMatch, bestSimilarity);
            return bestMatch;
        }

        log.debug("No good fuzzy match found for '{}' (best similarity: {:.2f})", input, bestSimilarity);
        return null;
    }

    /**
     * Find multiple close matches (top N suggestions)
     *
     * @param input The misspelled string
     * @param candidates List of valid strings to match against
     * @param maxSuggestions Maximum number of suggestions to return
     * @return List of closest matches, ordered by similarity
     */
    public static List<String> findClosestMatches(String input, List<String> candidates, int maxSuggestions) {
        if (input == null || input.isEmpty() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        String normalizedInput = input.toLowerCase();

        return candidates.stream()
            .map(candidate -> new Match(candidate, calculateSimilarity(normalizedInput, candidate.toLowerCase())))
            .filter(match -> match.similarity >= SIMILARITY_THRESHOLD)
            .sorted((a, b) -> Double.compare(b.similarity, a.similarity))
            .limit(maxSuggestions)
            .map(match -> match.value)
            .collect(Collectors.toList());
    }

    /**
     * Find top 3 suggestions
     */
    public static List<String> findClosestMatches(String input, List<String> candidates) {
        return findClosestMatches(input, candidates, MAX_SUGGESTIONS);
    }

    /**
     * Calculate similarity between two strings using Levenshtein distance
     * Returns a value between 0.0 (completely different) and 1.0 (identical)
     */
    private static double calculateSimilarity(String s1, String s2) {
        int distance = levenshteinDistance(s1, s2);
        int maxLength = Math.max(s1.length(), s2.length());

        if (maxLength == 0) {
            return 1.0;
        }

        return 1.0 - ((double) distance / maxLength);
    }

    /**
     * Calculate Levenshtein distance (edit distance) between two strings
     * This is the minimum number of single-character edits needed to transform one string into another
     */
    private static int levenshteinDistance(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();

        // Create a matrix to store distances
        int[][] dp = new int[len1 + 1][len2 + 1];

        // Initialize first row and column
        for (int i = 0; i <= len1; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= len2; j++) {
            dp[0][j] = j;
        }

        // Fill in the rest of the matrix
        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;

                dp[i][j] = Math.min(
                    Math.min(
                        dp[i - 1][j] + 1,     // deletion
                        dp[i][j - 1] + 1      // insertion
                    ),
                    dp[i - 1][j - 1] + cost   // substitution
                );
            }
        }

        return dp[len1][len2];
    }

    /**
     * Helper class to hold a match candidate with its similarity score
     */
    private static class Match {
        final String value;
        final double similarity;

        Match(String value, double similarity) {
            this.value = value;
            this.similarity = similarity;
        }
    }
}
