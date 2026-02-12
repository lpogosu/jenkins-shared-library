import org.lpogosu.pipeline.PipelineConfig
import org.lpogosu.pipeline.StepPolicy

/**
 * Checks out the revision Jenkins chose, then reports what that turned out to be.
 *
 * `checkout scm` on its own leaves the pipeline guessing. Nothing downstream should read
 * `env.GIT_COMMIT`, because on a change request Jenkins fills it with the id of a merge
 * commit it created for the build, and because it is empty entirely for some SCM
 * configurations. Resolving it once, here, and passing it explicitly is the difference
 * between an image label that is right and one that is right most of the time.
 *
 * @return {@code [commit: <40 hex>, lastReleaseTag: <tag or null>]}
 */
def call(Map args) {
    PipelineConfig config = args.config
    StepPolicy policy = config.policy('checkout')

    String commit = null
    String lastReleaseTag = null

    // One time budget for the whole step, with the retries inside it. The other way round —
    // a timeout per attempt — silently multiplies the wait by the retry count, so a step
    // documented as ten minutes can hold an executor for half an hour.
    timeout(time: policy.timeoutMinutes, unit: 'MINUTES') {
        // Checkout is the one step in this pipeline worth retrying by default: it is
        // idempotent, and its usual failure is a git server that was briefly unreachable.
        retry(policy.attempts) {
            checkout(scm)
        }

        commit = sh(script: 'git rev-parse HEAD', returnStdout: true, label: 'resolve revision').trim()
        if (!(commit ==~ /^[0-9a-f]{40}$/)) {
            error("scmCheckout: 'git rev-parse HEAD' returned '${commit}', which is not a commit id")
        }
        lastReleaseTag = resolveLastReleaseTag()
    }

    echo "revision ${commit.take(7)}, last release ${lastReleaseTag ?: 'none'}"
    return [commit: commit, lastReleaseTag: lastReleaseTag]
}

/**
 * The most recent release tag reachable from HEAD, or null.
 *
 * `git describe` exits non-zero when the history contains no tag, which is the normal
 * state of a service nobody has released yet — not a reason to fail the build. The
 * `--match` keeps a `nightly-2026-01-14` style tag from being mistaken for a version.
 */
private String resolveLastReleaseTag() {
    String output = sh(script: 'git describe --tags --abbrev=0 --match "v[0-9]*" 2>/dev/null || true',
                       returnStdout: true, label: 'last release tag').trim()
    return output ?: null
}
