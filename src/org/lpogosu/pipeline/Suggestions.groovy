package org.lpogosu.pipeline

import groovy.transform.CompileStatic

/**
 * "Did you mean" for configuration keys.
 *
 * Worth the thirty lines: the failure mode this replaces is a Jenkinsfile that quietly
 * ignores {@code buildTools: 'maven'} and then fails much later with something unrelated.
 */
@CompileStatic
class Suggestions implements Serializable {

    private static final long serialVersionUID = 1L

    /** Beyond two edits the suggestion stops being a correction and starts being a guess. */
    private static final int MAX_DISTANCE = 2

    /** Below this an abbreviation is too short to mean anything: 'de' matches half a ruleset. */
    private static final int MIN_ABBREVIATION = 3

    /**
     * The best candidate for a key somebody got wrong, or null.
     *
     * Two rules, because the mistakes have two shapes. Typos are close in edit distance —
     * {@code buildTools} against {@code buildTool}. Abbreviations are not: {@code prod} is
     * six edits from {@code production} and obviously means it.
     */
    static String closest(String unknown, Collection<String> candidates) {
        if (!unknown) {
            return null
        }
        String byEdits = closestByEdits(unknown, candidates)
        return byEdits ?: expansionOf(unknown, candidates)
    }

    private static String closestByEdits(String unknown, Collection<String> candidates) {
        String best = null
        int bestDistance = MAX_DISTANCE + 1
        // A copy, and sorted: the candidate collections are immutable, and two keys the same
        // distance away should always produce the same suggestion.
        List<String> ordered = new ArrayList<String>(candidates)
        Collections.sort(ordered)
        ordered.each { String candidate ->
            int distance = editDistance(unknown.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT))
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return bestDistance <= MAX_DISTANCE ? best : null
    }

    /** The only candidate the unknown key is a prefix of, if there is exactly one. */
    private static String expansionOf(String unknown, Collection<String> candidates) {
        if (unknown.length() < MIN_ABBREVIATION) {
            return null
        }
        String lower = unknown.toLowerCase(Locale.ROOT)
        List<String> matches = candidates.findAll { String candidate ->
            candidate.toLowerCase(Locale.ROOT).startsWith(lower)
        } as List<String>
        return matches.size() == 1 ? matches.first() : null
    }

    /** Levenshtein distance. */
    static int editDistance(String left, String right) {
        int[] previous = (0..right.length()).collect { Integer it -> it } as int[]
        int[] current = new int[right.length() + 1]
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1)
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            int[] swap = previous
            previous = current
            current = swap
        }
        return previous[right.length()]
    }

}
