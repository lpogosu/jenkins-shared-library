import org.lpogosu.pipeline.PipelineConfig
import org.lpogosu.pipeline.StepPolicy

/**
 * Runs the language's linter and records what it found.
 *
 * The quality gate is split rather than binary: an error fails the build, a warning marks
 * it unstable. A linter wired to fail on everything gets a blanket suppression file within
 * a month; one that fails on nothing is a report nobody opens. The split keeps the signal
 * that matters blocking and leaves the rest visible.
 *
 * `enabledForFailure: true` records the findings even when the analysis command itself
 * exits non-zero, which is exactly when the findings explain why.
 */
def call(Map args) {
    PipelineConfig config = args.config
    StepPolicy policy = config.policy('analysis')

    List gates = [
        [threshold: 1, type: 'TOTAL_ERROR', unstable: false],
        [threshold: 1, type: 'TOTAL_HIGH', unstable: true],
    ]

    timeout(time: policy.timeoutMinutes, unit: 'MINUTES') {
        switch (config.buildTool) {
            case 'maven':
                sh(label: 'checkstyle', script: 'mvn --batch-mode --no-transfer-progress checkstyle:checkstyle')
                recordIssues(enabledForFailure: true, qualityGates: gates,
                             tools: [checkStyle(pattern: '**/target/checkstyle-result.xml')])
                break
            case 'gradle':
                sh(label: 'spotbugs', script: './gradlew --no-daemon spotbugsMain')
                recordIssues(enabledForFailure: true, qualityGates: gates,
                             tools: [spotBugs(pattern: '**/build/reports/spotbugs/main.xml')])
                break
            case 'npm':
                // `|| true`, because eslint's exit code says "there were findings" and the
                // quality gate below is the thing that decides what to do about that. Two
                // components deciding independently is how a gate gets bypassed by accident.
                sh(label: 'eslint', script: 'mkdir -p reports && npx eslint . --format json --output-file reports/eslint.json || true')
                recordIssues(enabledForFailure: true, qualityGates: gates,
                             tools: [esLint(pattern: 'reports/eslint.json')])
                break
            case 'docker':
                withEnv(["DOCKERFILE=${config.image ? config.image.dockerfile : 'Dockerfile'}"]) {
                    sh(label: 'hadolint',
                       script: 'mkdir -p reports && docker run --rm -i hadolint/hadolint:2.12.0 ' +
                               'hadolint --format checkstyle - < "$DOCKERFILE" > reports/hadolint.xml || true')
                }
                recordIssues(enabledForFailure: true, qualityGates: gates,
                             tools: [checkStyle(pattern: 'reports/hadolint.xml')])
                break
        }
    }
}
