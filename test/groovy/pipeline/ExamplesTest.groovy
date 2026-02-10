package pipeline

import static org.assertj.core.api.Assertions.assertThat

import org.junit.Before
import org.junit.Test

/**
 * Runs the Jenkinsfiles in {@code examples/}.
 *
 * They are the first thing anybody adopting this library copies, so they are the worst
 * place for a stale option name. Executing them here means a rename that breaks them breaks
 * the build instead of somebody else's afternoon.
 */
class ExamplesTest extends LibraryTestBase {

    @Override
    @Before
    void setUp() throws Exception {
        super.setUp()
        addEnvVar('BRANCH_NAME', 'main')
        addEnvVar('BUILD_NUMBER', '87')
    }

    @Test
    void theFullExampleBuildsPublishesAndDeploys() {
        runScript('Jenkinsfile.after')

        assertThat(stages()).containsExactly('Checkout', 'Build', 'Verify', 'Image', 'Deploy: staging')
        assertJobStatusSuccess()
    }

    @Test
    void theMinimalExampleStopsAfterTheChecks() {
        runScript('Jenkinsfile.minimal')

        assertThat(stages()).containsExactly('Checkout', 'Build', 'Verify')
        assertThat(called('withCredentials')).isFalse()
        assertJobStatusSuccess()
    }

    @Test
    void theTunedExampleAppliesItsOverridesAndKeepsTheRest() {
        runScript('Jenkinsfile.tuned')

        List<Integer> timeouts = argumentsOf('timeout').collect { (it as Map)['time'] as Integer }
        assertThat(timeouts).contains(50, 45)
        assertThat(timeouts).as('the build step was not overridden and keeps its default').contains(30)
        assertThat(shellScripts().find { it.contains('docker buildx build') })
            .contains('--file docker/Dockerfile')
            .contains('--build-arg JDK_VERSION=21')
        assertJobStatusSuccess()
    }

    @Test
    void theBeforeExampleIsNotSomethingThisLibraryClaimsToRun() {
        // examples/Jenkinsfile.before is a declarative pipeline kept as a comparison. It is
        // deliberately absent from this suite, and this test says so out loud rather than
        // leaving a reader to wonder whether it was forgotten.
        assertThat(new File('examples/Jenkinsfile.before')).exists()
        assertThat(new File('examples/Jenkinsfile.before').getText('UTF-8')).startsWith('// The Jenkinsfile this library replaces.')
    }

    private List<String> shellScripts() {
        return helper.callStack
            .findAll { it.methodName == 'sh' }
            .collect { (it.args[0] as Map)['script'] as String }
    }

}
