package org.lpogosu.pipeline

import java.util.regex.Pattern

import groovy.transform.CompileStatic

/**
 * Which tags an image gets, and what a deploy is allowed to reference.
 *
 * The rules encode two decisions worth stating out loud:
 *
 * A pre-release never moves a shared tag. {@code v2.0.0-rc.1} gets exactly one tag. It does
 * not touch {@code latest}, {@code 2.0} or {@code 2} — those are what a human types when they
 * mean "the current release", and a release candidate is not that.
 *
 * Deploys reference a digest, never a tag. Every tag this class produces is mutable by
 * definition, including {@code 1.4.0}: a registry will happily accept a second push under
 * the same tag. Between an approval and a rollout that is a window someone can walk through.
 */
@CompileStatic
class ImageTags implements Serializable {

    private static final long serialVersionUID = 1L

    /** The tag grammar the OCI distribution spec defines. */
    static final Pattern VALID_TAG = Pattern.compile('^[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}$')

    static final Pattern VALID_DIGEST = Pattern.compile('^sha256:[0-9a-f]{64}$')

    static final int MAX_TAG_LENGTH = 128

    private static final Pattern INVALID_TAG_CHARS = Pattern.compile('[^a-zA-Z0-9._-]+')

    /**
     * The tags to publish for a build.
     *
     * Topic branches get nothing: they never reach the registry, so naming them there is
     * an invitation to garbage collect later.
     */
    static List<String> forContext(BuildContext context, String version) {
        switch (context.type) {
            case BranchType.RELEASE_TAG:
                return forRelease(context.tag)
            case BranchType.TRUNK:
                return [sanitize(version), candidateTag(context), 'main']
            case BranchType.PULL_REQUEST:
                // Built to prove the Dockerfile still works, not pushed. The tag exists so
                // that `docker images` on the agent is readable.
                return ["pr-${context.changeId}".toString()]
            default:
                return []
        }
    }

    /**
     * Release tags: the exact version, plus the moving major and minor aliases and
     * {@code latest} — but only for a final release.
     */
    static List<String> forRelease(String tag) {
        SemanticVersion version = SemanticVersion.parse(tag)
        if (version.preRelease) {
            return [version.toString()]
        }
        return [
            version.toString(),
            "${version.major}.${version.minor}".toString(),
            "${version.major}".toString(),
            'latest',
        ]
    }

    /**
     * The tag a commit's image is published under and later promoted from.
     *
     * Content-addressed by the source revision rather than by the branch, so that
     * promoting a release tag is a lookup and not a rebuild.
     */
    static String candidateTag(BuildContext context) {
        if (!context.commit) {
            throw new IllegalStateException('candidateTag needs the commit; call it after the checkout step')
        }
        return "sha-${context.shortCommit}".toString()
    }

    /**
     * Coerces a string into a legal Docker tag.
     *
     * Nothing here should ever have to do real work — versions and short shas are already
     * legal. It exists because a branch name reaches this code eventually, and a registry
     * rejecting a tag at the end of a thirty-minute build is a bad way to find out.
     */
    static String sanitize(String raw) {
        String tag = INVALID_TAG_CHARS.matcher(raw ?: '').replaceAll('-')
        // The first character has a narrower alphabet than the rest.
        tag = tag.replaceAll('^[._-]+', '')
        if (tag.length() > MAX_TAG_LENGTH) {
            tag = tag.substring(0, MAX_TAG_LENGTH)
        }
        tag = tag.replaceAll('[._-]+$', '')
        if (!tag) {
            throw new IllegalArgumentException("'${raw}' contains no character that is legal in a Docker tag".toString())
        }
        return tag
    }

    /**
     * The immutable reference a deploy uses.
     *
     * @throws IllegalArgumentException if the digest is not a sha256 digest — the point of
     *         this reference is that it cannot be moved, and a malformed one silently
     *         becomes a tag again
     */
    static String digestReference(String repository, String digest) {
        if (!repository) {
            throw new IllegalArgumentException('repository is required to build a digest reference')
        }
        if (!digest || !VALID_DIGEST.matcher(digest).matches()) {
            throw new IllegalArgumentException("'${digest}' is not a sha256 digest".toString())
        }
        return "${repository}@${digest}".toString()
    }

    static boolean isDigestReference(String reference) {
        if (!reference || !reference.contains('@')) {
            return false
        }
        int split = reference.indexOf('@')
        return VALID_DIGEST.matcher(reference.substring(split + 1)).matches()
    }

}
