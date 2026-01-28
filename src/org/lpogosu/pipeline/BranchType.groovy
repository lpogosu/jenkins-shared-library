package org.lpogosu.pipeline

/**
 * What kind of build this is.
 *
 * Decided once, in {@link BuildContext#fromEnvironment}, so that no step has to parse
 * {@code BRANCH_NAME} a second time and reach a different conclusion.
 */
enum BranchType {

    /** A change request build. Jenkins builds a merge commit that exists in no branch. */
    PULL_REQUEST,

    /** A build of the integration branch: {@code main} or {@code master}. */
    TRUNK,

    /** A build of a tag that parses as a semantic version. Promotes, never rebuilds. */
    RELEASE_TAG,

    /** Anything else: topic branches, and tags that are not release tags. */
    FEATURE

}
