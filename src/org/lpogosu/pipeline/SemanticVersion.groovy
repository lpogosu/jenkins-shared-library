package org.lpogosu.pipeline

import java.util.regex.Matcher
import java.util.regex.Pattern

import groovy.transform.CompileStatic

/**
 * A semantic version, parsed strictly and compared by the precedence rules of semver 2.0.0.
 *
 * String comparison of versions is the bug this class exists to prevent: it puts
 * {@code 1.10.0} before {@code 1.9.0} and {@code 1.0.0-rc.1} after {@code 1.0.0}, which is
 * how a release candidate ends up wearing the {@code latest} tag.
 */
@CompileStatic
class SemanticVersion implements Comparable<SemanticVersion>, Serializable {

    private static final long serialVersionUID = 1L

    /** The regular expression published at semver.org, with the named groups numbered. */
    private static final Pattern PATTERN = Pattern.compile(
        '^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)' +
        '(?:-((?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)' +
        '(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?' +
        '(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$')

    private static final Pattern NUMERIC = Pattern.compile('^(0|[1-9]\\d*)$')

    final int major
    final int minor
    final int patch

    /** The dot-separated pre-release identifiers, or null for a release. */
    final String preRelease

    /** Build metadata. Carried around, ignored by every comparison, per the spec. */
    final String buildMetadata

    SemanticVersion(int major, int minor, int patch, String preRelease = null, String buildMetadata = null) {
        this.major = major
        this.minor = minor
        this.patch = patch
        this.preRelease = preRelease ?: null
        this.buildMetadata = buildMetadata ?: null
    }

    /**
     * Parses a version, tolerating the leading {@code v} that git tags conventionally carry.
     *
     * @throws IllegalArgumentException if the string is not a semantic version
     */
    static SemanticVersion parse(String raw) {
        if (!raw) {
            throw new IllegalArgumentException('version string is null or empty')
        }
        String stripped = raw.trim().startsWith('v') ? raw.trim().substring(1) : raw.trim()
        Matcher matcher = PATTERN.matcher(stripped)
        if (!matcher.matches()) {
            throw new IllegalArgumentException("'${raw}' is not a semantic version (expected MAJOR.MINOR.PATCH)".toString())
        }
        return new SemanticVersion(
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3)),
            matcher.group(4),
            matcher.group(5))
    }

    static boolean isValid(String raw) {
        if (!raw) {
            return false
        }
        String stripped = raw.trim().startsWith('v') ? raw.trim().substring(1) : raw.trim()
        return PATTERN.matcher(stripped).matches()
    }

    /** Named `has` rather than `is` so it does not shadow the {@code preRelease} property. */
    boolean hasPreRelease() {
        return preRelease != null
    }

    /**
     * The next patch release. Used as the base of every pre-release version built between
     * two tags, so that {@code 1.4.3-main.87} sorts below the {@code 1.4.3} it precedes.
     */
    SemanticVersion nextPatch() {
        return new SemanticVersion(major, minor, patch + 1)
    }

    SemanticVersion withPreRelease(String identifiers) {
        return new SemanticVersion(major, minor, patch, identifiers, buildMetadata)
    }

    @Override
    String toString() {
        StringBuilder text = new StringBuilder("${major}.${minor}.${patch}".toString())
        if (preRelease) {
            text.append('-').append(preRelease)
        }
        if (buildMetadata) {
            text.append('+').append(buildMetadata)
        }
        return text.toString()
    }

    @Override
    int compareTo(SemanticVersion other) {
        int byNumbers = [major <=> other.major, minor <=> other.minor, patch <=> other.patch].find { it != 0 } ?: 0
        if (byNumbers != 0) {
            return byNumbers
        }
        // A version with a pre-release has lower precedence than the release itself.
        if (preRelease == null && other.preRelease == null) {
            return 0
        }
        if (preRelease == null) {
            return 1
        }
        if (other.preRelease == null) {
            return -1
        }
        return comparePreRelease(preRelease, other.preRelease)
    }

    private static int comparePreRelease(String left, String right) {
        List<String> a = left.split('\\.') as List<String>
        List<String> b = right.split('\\.') as List<String>
        int shared = Math.min(a.size(), b.size())
        for (int i = 0; i < shared; i++) {
            int result = compareIdentifier(a[i], b[i])
            if (result != 0) {
                return result
            }
        }
        // "A larger set of pre-release fields has a higher precedence than a smaller set,
        // if all of the preceding identifiers are equal."
        return a.size() <=> b.size()
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = NUMERIC.matcher(left).matches()
        boolean rightNumeric = NUMERIC.matcher(right).matches()
        if (leftNumeric && rightNumeric) {
            return Integer.parseInt(left) <=> Integer.parseInt(right)
        }
        // "Numeric identifiers always have lower precedence than alphanumeric identifiers."
        if (leftNumeric) {
            return -1
        }
        if (rightNumeric) {
            return 1
        }
        return left <=> right
    }

    @Override
    boolean equals(Object other) {
        if (!(other instanceof SemanticVersion)) {
            return false
        }
        return (this <=> (SemanticVersion) other) == 0
    }

    @Override
    int hashCode() {
        return Objects.hash(major, minor, patch, preRelease)
    }

}
