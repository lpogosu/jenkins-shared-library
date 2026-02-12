import org.lpogosu.pipeline.PipelineConfig
import org.lpogosu.pipeline.StepPolicy

/**
 * Compiles and packages, without running tests.
 *
 * Tests live in their own stage so that a red suite is visibly a test failure rather than
 * "the build stage went red", and so that the report is published even when they fail.
 *
 * Every command is a single-quoted string. The version reaches the tool through the
 * environment, not through Groovy interpolation, which keeps this step written the same
 * way as the ones that handle credentials — the shape of a command should not tell you
 * whether it was safe to interpolate into.
 */
def call(Map args) {
    PipelineConfig config = args.config
    String version = args.version
    StepPolicy policy = config.policy('build')

    timeout(time: policy.timeoutMinutes, unit: 'MINUTES') {
        withEnv(["BUILD_VERSION=${version}"]) {
            switch (config.buildTool) {
                case 'maven':
                    sh(label: 'mvn package',
                       script: 'mvn --batch-mode --no-transfer-progress -DskipTests -Drevision="$BUILD_VERSION" package')
                    break
                case 'gradle':
                    sh(label: 'gradle assemble',
                       script: './gradlew --no-daemon -Pversion="$BUILD_VERSION" assemble')
                    break
                case 'npm':
                    // `npm ci` and not `npm install`: install rewrites the lockfile when it
                    // disagrees with package.json, so the build quietly stops testing what
                    // the lockfile says and there is no record of it having happened.
                    sh(label: 'npm ci', script: 'npm ci --no-audit --no-fund')
                    sh(label: 'npm run build', script: 'npm run build')
                    break
                case 'docker':
                    echo 'buildTool=docker: the image build is the build, see the Image stage'
                    break
            }
        }
    }
}
