package org.lpogosu.pipeline

import groovy.transform.CompileStatic

/**
 * Everything the pipeline knows about the revision under build, derived once from the
 * environment variables a multibranch job provides.
 *
 * The environment is passed in as an explicit map rather than read from the {@code env}
 * global on purpose: the exact set of variables this library depends on is then visible in
 * one place, and the class stays testable without a Jenkins instance.
 */
@CompileStatic
class BuildContext implements Serializable {

    private static final long serialVersionUID = 1L

    /** Branch names treated as the integration branch. */
    static final List<String> TRUNK_BRANCHES = ['main', 'master'].asImmutable()

    /** Environment variables read by {@link #fromEnvironment}. */
    static final List<String> REQUIRED_VARIABLES = ['BRANCH_NAME', 'BUILD_NUMBER'].asImmutable()

    BranchType type
    String branch
    String tag
    String changeId
    String changeTarget
    int buildNumber

    /** Filled in by the checkout step, once the revision is actually on disk. */
    String commit

    /**
     * Classifies the build.
     *
     * Precedence matters and is not obvious: {@code CHANGE_ID} wins over everything,
     * because on a change request Jenkins also sets {@code BRANCH_NAME} — to {@code PR-42},
     * or, for a change request opened from a fork's default branch, to something that looks
     * exactly like trunk. Getting that order wrong deploys a fork's code to staging.
     */
    static BuildContext fromEnvironment(Map environment) {
        List<String> missing = REQUIRED_VARIABLES.findAll { String name -> !environment[name] }
        if (missing) {
            throw new ConfigurationException([
                "${missing.join(' and ')} is not set. standardPipeline needs the branch a build belongs to; \
run it from a multibranch pipeline or a Jenkinsfile-from-SCM job rather than a plain pipeline job.".toString(),
            ])
        }

        String branch = environment['BRANCH_NAME'] as String
        String tag = environment['TAG_NAME'] as String
        String changeId = environment['CHANGE_ID'] as String
        BuildContext context = new BuildContext(
            branch: branch,
            tag: tag,
            changeId: changeId,
            changeTarget: environment['CHANGE_TARGET'] as String,
            buildNumber: Integer.parseInt(environment['BUILD_NUMBER'] as String))

        if (changeId) {
            context.type = BranchType.PULL_REQUEST
        } else if (tag && SemanticVersion.isValid(tag)) {
            context.type = BranchType.RELEASE_TAG
        } else if (!tag && TRUNK_BRANCHES.contains(branch)) {
            context.type = BranchType.TRUNK
        } else {
            // A tag that is not a release tag is not an error — annotated tags get pushed for
            // all sorts of reasons. It just gets the topic-branch treatment: build, test,
            // publish nothing.
            context.type = BranchType.FEATURE
        }
        return context
    }

    String getShortCommit() {
        return commit ? commit.substring(0, 7) : null
    }

    /** True when this build is allowed to push images and deploy. */
    boolean isPublishing() {
        return type == BranchType.TRUNK
    }

    /** True when an image should be produced at all, whether or not it is pushed. */
    boolean isBuildingImage() {
        return type == BranchType.TRUNK || type == BranchType.PULL_REQUEST
    }

    /** True when this build promotes an image a trunk build already produced. */
    boolean isPromotion() {
        return type == BranchType.RELEASE_TAG
    }

    /**
     * A readable identity, for the same reason as {@link PipelineConfig#toString}: an
     * identity hash tells nobody anything and changes on every run.
     */
    @Override
    String toString() {
        return "BuildContext(${type}, ${tag ?: branch}, build ${buildNumber}, ${shortCommit ?: 'no revision yet'})"
    }

    /** A one-line description for the build log, so the classification is never a guess. */
    String describe() {
        switch (type) {
            case BranchType.PULL_REQUEST:
                return "change request ${changeId} into ${changeTarget ?: 'unknown target'} (build ${buildNumber})".toString()
            case BranchType.RELEASE_TAG:
                return "release tag ${tag} (build ${buildNumber})".toString()
            case BranchType.TRUNK:
                return "trunk ${branch} (build ${buildNumber})".toString()
            default:
                return "topic branch ${branch} (build ${buildNumber})".toString()
        }
    }

}
