package org.lpogosu.pipeline

import java.util.regex.Pattern

import groovy.transform.CompileStatic

/**
 * Turns "which commit is this" into "what is this called".
 *
 * A release tag is its own version. Everything else gets a pre-release version derived
 * from the next patch of the last release, so the ordering the build produces is the
 * ordering semver defines:
 *
 * <pre>
 *   1.4.2  &lt;  1.4.3-feature-checkout-timeout.4  &lt;  1.4.3-main.87  &lt;  1.4.3
 * </pre>
 *
 * That property is what makes it safe for a package manager, a Helm chart or an operator
 * to compare two versions from this pipeline and get the right answer. Suffixing the
 * <em>previous</em> release instead — the common {@code 1.4.2-main.87} shape — puts every
 * development build below a release that already shipped.
 */
@CompileStatic
class VersionResolver implements Serializable {

    private static final long serialVersionUID = 1L

    /** Used when a repository has no release tag yet, so the first trunk build is 0.0.1-main.1. */
    private static final SemanticVersion SEED = new SemanticVersion(0, 0, 0)

    private static final int MAX_IDENTIFIER_LENGTH = 40

    private static final Pattern INVALID_IDENTIFIER_CHARS = Pattern.compile('[^0-9a-z-]+')
    private static final Pattern LEADING_ZERO_NUMERIC = Pattern.compile('^0\\d+$')

    /**
     * @param context         the build being named
     * @param lastReleaseTag  the most recent release tag reachable from this commit, or null
     */
    static String resolve(BuildContext context, String lastReleaseTag) {
        if (context.type == BranchType.RELEASE_TAG) {
            // The tag is the version. Decorating it would mean the artifact and the tag
            // people were told to deploy do not match.
            return SemanticVersion.parse(context.tag).toString()
        }

        SemanticVersion base = (lastReleaseTag && SemanticVersion.isValid(lastReleaseTag))
            ? SemanticVersion.parse(lastReleaseTag).nextPatch()
            : SEED.nextPatch()

        String identifiers
        switch (context.type) {
            case BranchType.TRUNK:
                identifiers = "main.${context.buildNumber}".toString()
                break
            case BranchType.PULL_REQUEST:
                identifiers = "pr.${context.changeId}.${context.buildNumber}".toString()
                break
            default:
                identifiers = "${preReleaseIdentifier(context.branch)}.${context.buildNumber}".toString()
                break
        }

        String version = base.withPreRelease(identifiers).toString()
        // A branch name can contain anything a git ref can contain. If sanitising it ever
        // produces something semver rejects, that is a bug here, and it should surface as
        // one rather than as an unreadable failure in whatever consumes the version later.
        if (!SemanticVersion.isValid(version)) {
            throw new IllegalStateException(
                "resolved version '${version}' is not valid semver (branch '${context.branch}')".toString())
        }
        return version
    }

    /**
     * Reduces a git ref to a single semver pre-release identifier.
     *
     * Semver allows only {@code [0-9A-Za-z-]} and forbids numeric identifiers with a
     * leading zero, which a branch called {@code 007} would otherwise produce.
     */
    static String preReleaseIdentifier(String branch) {
        String slug = (branch ?: '').toLowerCase(Locale.ROOT)
        slug = INVALID_IDENTIFIER_CHARS.matcher(slug).replaceAll('-')
        slug = slug.replaceAll('-{2,}', '-')
        slug = slug.replaceAll('^-+', '').replaceAll('-+$', '')
        if (slug.length() > MAX_IDENTIFIER_LENGTH) {
            slug = slug.substring(0, MAX_IDENTIFIER_LENGTH).replaceAll('-+$', '')
        }
        if (!slug) {
            return 'branch'
        }
        if (LEADING_ZERO_NUMERIC.matcher(slug).matches()) {
            return "b${slug}"
        }
        return slug
    }

}
