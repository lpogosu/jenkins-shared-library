import org.lpogosu.pipeline.PipelineConfig
import org.lpogosu.pipeline.StepPolicy

/**
 * Runs the unit tests and publishes the report.
 *
 * The `finally` is the entire point of this step existing. When the test command exits
 * non-zero, `sh` throws, and a straight-line stage never reaches the publisher — so the
 * one build whose report anyone actually wants is the one build that has no report, and
 * the person debugging it is left reading console output. Publishing in `finally` costs
 * one line and removes that.
 *
 * `allowEmptyResults: false` is the second half. A test command that exits 0 without
 * running anything — a bad include pattern, a module that stopped being built — looks
 * exactly like a passing suite from the outside. Requiring at least one result makes the
 * difference visible.
 */
def call(Map args) {
    PipelineConfig config = args.config
    if (config.buildTool == 'docker') {
        // Nothing generic to run: an image-only repository is expected to test inside its
        // own build. Pretending otherwise would publish an empty report every build.
        echo 'buildTool=docker: no unit test command, the image build is expected to run its own'
        return
    }

    StepPolicy policy = config.policy('test')

    try {
        timeout(time: policy.timeoutMinutes, unit: 'MINUTES') {
            switch (config.buildTool) {
                case 'maven':
                    sh(label: 'mvn test', script: 'mvn --batch-mode --no-transfer-progress test')
                    break
                case 'gradle':
                    sh(label: 'gradle test', script: './gradlew --no-daemon test')
                    break
                case 'npm':
                    sh(label: 'npm test', script: 'npm test')
                    break
            }
        }
    } finally {
        junit(testResults: config.testResults, allowEmptyResults: false, keepLongStdio: true)
    }
}
