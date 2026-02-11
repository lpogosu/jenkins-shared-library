package pipeline

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

import java.util.regex.Pattern

import org.junit.Before
import org.junit.Test

/**
 * The individual steps, in the situations where they are easy to get wrong.
 */
class StepBehaviourTest extends LibraryTestBase {

    @Override
    @Before
    void setUp() throws Exception {
        super.setUp()
        addEnvVar('BUILD_NUMBER', '87')
        addEnvVar('BRANCH_NAME', 'main')
    }

    @Test
    void theTestReportIsPublishedEvenWhenTheSuiteFails() {
        helper.addShMock(Pattern.compile('mvn .* test'), 'there were failing tests', 1)

        assertThatThrownBy { runScript('standard.Jenkinsfile') }.isInstanceOf(Exception)

        assertThat(called('junit'))
            .as('the one build whose report anybody wants is the one that failed')
            .isTrue()
    }

    @Test
    void aSuiteThatRanNothingIsNotTreatedAsAPass() {
        runScript('standard.Jenkinsfile')

        Map publish = argumentsOf('junit').first() as Map
        assertThat(publish['allowEmptyResults'])
            .as('a broken include pattern looks exactly like a green suite from outside')
            .isEqualTo(false)
        assertThat(publish['testResults']).isEqualTo('**/target/surefire-reports/TEST-*.xml')
    }

    @Test
    void checkoutIsRetriedAndTheRestIsNot() {
        runScript('standard.Jenkinsfile')

        List<Integer> retries = argumentsOf('retry').collect { it as Integer }
        assertThat(retries)
            .as('checkout is idempotent and its usual failure is a git server blinking')
            .containsExactly(3, 2)
    }

    @Test
    void everyExternalCallHasATimeout() {
        runScript('standard.Jenkinsfile')

        List<String> unwrapped = (0..<helper.callStack.size())
            .findAll { int i -> helper.callStack[i].methodName == 'sh' }
            .findAll { int i -> !enclosingSteps(i).contains('timeout') }
            .collect { int i -> (helper.callStack[i].args[0] as Map)['label'] as String }

        assertThat(unwrapped)
            .as('a step without a timeout holds an executor until somebody notices, which is tomorrow')
            .isEmpty()
    }

    @Test
    void aRevisionThatIsNotACommitIdStopsTheBuild() {
        helper.addShMock(Pattern.compile('git rev-parse HEAD'), 'fatal: not a git repository', 0)

        assertThatThrownBy { runScript('standard.Jenkinsfile') }
            .isInstanceOf(PipelineAborted)
            .hasMessageContaining('which is not a commit id')
    }

    @Test
    void aRepositoryWithNoReleaseTagYetStillGetsAVersion() {
        helper.addShMock(Pattern.compile('git describe --tags.*'), '', 0)

        runScript('standard.Jenkinsfile')

        assertThat(binding.getVariable('currentBuild')['displayName'] as String).endsWith('0.0.1-main.87')
    }

    @Test
    void aReleaseTagWithNoPublishedImageSaysWhatToDoAboutIt() {
        addEnvVar('BRANCH_NAME', 'v1.5.0')
        addEnvVar('TAG_NAME', 'v1.5.0')
        helper.addShMock(Pattern.compile('docker buildx imagetools inspect --format.*'), '', 0)

        assertThatThrownBy { runScript('standard.Jenkinsfile') }
            .isInstanceOf(PipelineAborted)
            .hasMessageContaining('has no published image')
            .hasMessageContaining('Tag a commit that has been built on main')
    }

    @Test
    void theRegistrySessionIsClosedEvenWhenThePushFails() {
        helper.addShMock(Pattern.compile('docker buildx build.*', Pattern.DOTALL), 'denied', 1)

        assertThatThrownBy { runScript('standard.Jenkinsfile') }.isInstanceOf(Exception)

        assertThat(shellLabels())
            .as('a login left behind is a credential the next job on this agent inherits')
            .contains('registry logout')
    }

    @Test
    void theImageCarriesTheRevisionItWasBuiltFrom() {
        runScript('standard.Jenkinsfile')

        String build = shellScripts().find { it.contains('docker buildx build') }
        assertThat(build).contains("--label org.opencontainers.image.revision=${COMMIT}")
    }

    @Test
    void theRegistryLoginRunsBeforeAnythingIsPushed() {
        runScript('standard.Jenkinsfile')

        List<String> labels = shellLabels()
        assertThat(labels.indexOf('registry login')).isLessThan(labels.findIndexOf { it.startsWith('docker build and push') })
    }

    /**
     * The steps still open around the call at {@code index}.
     *
     * JenkinsPipelineUnit records a nesting depth with every call, so walking backwards and
     * keeping each entry shallower than the last reconstructs the enclosing blocks.
     */
    private List<String> enclosingSteps(int index) {
        List<String> enclosing = []
        int depth = helper.callStack[index].stackDepth
        for (int i = index - 1; i >= 0; i--) {
            if (helper.callStack[i].stackDepth < depth) {
                depth = helper.callStack[i].stackDepth
                enclosing << helper.callStack[i].methodName
            }
        }
        return enclosing
    }

    private List<String> shellLabels() {
        return helper.callStack
            .findAll { it.methodName == 'sh' }
            .collect { (it.args[0] as Map)['label'] as String }
    }

    private List<String> shellScripts() {
        return helper.callStack
            .findAll { it.methodName == 'sh' }
            .collect { (it.args[0] as Map)['script'] as String }
    }

}
