package pipeline

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

import java.util.regex.Pattern

import org.junit.Before
import org.junit.Test

/**
 * What the pipeline does for each kind of build.
 *
 * The stage sequence is the contract of this library: it is the thing a Jenkinsfile stops
 * spelling out, and therefore the thing nobody reviews any more. Pinning it per branch type
 * is what makes "make feature branches skip the image build" a change with a failing test
 * rather than a change somebody notices three weeks later on a release.
 */
class StandardPipelineTest extends LibraryTestBase {

    @Override
    @Before
    void setUp() throws Exception {
        super.setUp()
    }

    @Test
    void topicBranchStopsAfterTheChecks() {
        onBranch('feature/checkout-timeout')

        runScript('standard.Jenkinsfile')

        assertThat(stages()).containsExactly('Checkout', 'Build', 'Verify')
        assertJobStatusSuccess()
        testNonRegression('feature')
    }

    @Test
    void changeRequestBuildsTheImageWithoutPushingIt() {
        onChangeRequest('42', 'main')

        runScript('standard.Jenkinsfile')

        assertThat(stages()).containsExactly('Checkout', 'Build', 'Verify', 'Image (no push)')
        assertThat(shellCommands().findAll { it.contains('docker buildx build') })
            .as('a change request builds the image')
            .hasSize(1)
        assertThat(shellCommands().join('\n'))
            .as('and never pushes it, nor logs in to a registry it has no business writing to')
            .doesNotContain('--push')
            .doesNotContain('docker login')
        testNonRegression('change_request')
    }

    @Test
    void trunkPublishesTheImageAndDeploysToStaging() {
        onBranch('main')

        runScript('standard.Jenkinsfile')

        assertThat(stages()).containsExactly('Checkout', 'Build', 'Verify', 'Image', 'Deploy: staging')
        assertThat(shellCommands().find { it.contains('docker buildx build') })
            .contains('--push')
            .contains("--tag registry.example.internal/platform/checkout-api:sha-${SHORT_COMMIT}")
            .contains('--tag registry.example.internal/platform/checkout-api:main')
        testNonRegression('trunk')
    }

    @Test
    void releaseTagPromotesTheTrunkImageInsteadOfRebuildingIt() {
        onTag('v1.5.0')

        runScript('standard.Jenkinsfile')

        assertThat(stages())
            .containsExactly('Checkout', 'Resolve release candidate', 'Promote image', 'Deploy: production')
        assertThat(shellCommands().join('\n'))
            .as('a release must ship the bytes trunk tested, so nothing is compiled or built again')
            .doesNotContain('docker buildx build')
            .doesNotContain('mvn')
        testNonRegression('release_tag')
    }

    @Test
    void releaseTagsPointEveryAliasAtTheDigestTrunkPublished() {
        onTag('v1.5.0')

        runScript('standard.Jenkinsfile')

        String promote = shellCommands().find { it.contains('imagetools create') }
        assertThat(promote)
            .contains('--tag registry.example.internal/platform/checkout-api:1.5.0')
            .contains('--tag registry.example.internal/platform/checkout-api:1.5')
            .contains('--tag registry.example.internal/platform/checkout-api:1')
            .contains('--tag registry.example.internal/platform/checkout-api:latest')
            .endsWith("registry.example.internal/platform/checkout-api@${DIGEST}")
    }

    @Test
    void aReleaseCandidateTagNeverMovesLatest() {
        onTag('v2.0.0-rc.1')

        runScript('standard.Jenkinsfile')

        String promote = shellCommands().find { it.contains('imagetools create') }
        assertThat(promote).contains('--tag registry.example.internal/platform/checkout-api:2.0.0-rc.1')
        assertThat(promote)
            .as('latest and the major and minor aliases mean "the current release"')
            .doesNotContain(':latest')
            .doesNotContain(':2.0 ')
            .doesNotContain(':2 ')
    }

    @Test
    void trunkVersionSortsBelowTheReleaseItPrecedes() {
        onBranch('main')

        runScript('standard.Jenkinsfile')

        // The last release tag the checkout reports is v1.4.2, so the next release is 1.4.3
        // and this build is a pre-release of it.
        assertThat(displayName()).endsWith('1.4.3-main.87')
    }

    @Test
    void aRepositoryWithoutAnImageStopsAfterTheChecksEvenOnTrunk() {
        onBranch('main')

        runScript('library.Jenkinsfile')

        assertThat(stages()).containsExactly('Checkout', 'Build', 'Verify')
        assertThat(called('withCredentials')).isFalse()
    }

    @Test
    void bothChecksRunInParallelWithFailFast() {
        onBranch('main')

        runScript('standard.Jenkinsfile')

        Map branches = helper.callStack.find { it.methodName == 'parallel' }.args[0] as Map
        assertThat(branches.keySet()).containsExactlyInAnyOrder('failFast', 'Unit tests', 'Static analysis')
        assertThat(branches['failFast']).isEqualTo(true)
    }

    @Test
    void anInvalidJenkinsfileFailsBeforeAnExecutorIsTakenAndListsEveryProblem() {
        onBranch('main')

        assertThatThrownBy { runScript('invalid.Jenkinsfile') }
            .isInstanceOf(PipelineAborted)
            .hasMessageContaining("unknown option 'buildTools'")
            .hasMessageContaining("Did you mean 'buildTool'")
            .hasMessageContaining("'name': 'Checkout API' is not usable as a release name")
            .hasMessageContaining("'buildTool': required")
            .hasMessageContaining("environments.prod: unknown environment")
            .hasMessageContaining("Did you mean 'production'")
            .hasMessageContaining("there is no 'image' block")

        assertThat(called('node'))
            .as('validation happens on the controller; a typo must not cost an executor')
            .isFalse()
        assertJobStatusFailure()
    }

    @Test
    void theWorkspaceIsCleanedAndTheTeamIsToldWhenAStageFails() {
        onBranch('main')
        helper.addShMock(Pattern.compile('mvn .*package.*'), 'compilation failure', 1)

        assertThatThrownBy { runScript('standard.Jenkinsfile') }.isInstanceOf(Exception)

        assertThat(called('cleanWs')).as('a workspace left behind fills an agent disk').isTrue()
        Map notification = argumentsOf('slackSend').first() as Map
        assertThat(notification['message']).contains('FAILURE')
        assertThat(notification['color']).isEqualTo('danger')
    }

    @Test
    void aGreenTopicBranchDoesNotNotifyAnyone() {
        onBranch('feature/checkout-timeout')

        runScript('standard.Jenkinsfile')

        assertThat(called('slackSend')).isFalse()
        assertThat(called('mail')).isFalse()
    }

    @Test
    void aBrokenTopicBranchDoesNotifyTheChannelButNotByMail() {
        onBranch('feature/checkout-timeout')
        helper.addShMock(Pattern.compile('mvn .*package.*'), 'compilation failure', 1)

        assertThatThrownBy { runScript('standard.Jenkinsfile') }.isInstanceOf(Exception)

        assertThat(called('slackSend')).isTrue()
        assertThat(called('mail')).isTrue()
    }

    private List<String> shellCommands() {
        return helper.callStack
            .findAll { it.methodName == 'sh' }
            .collect { it.args[0] instanceof Map ? (it.args[0] as Map)['script'] as String : it.args[0] as String }
    }

    private String displayName() {
        return binding.getVariable('currentBuild')['displayName'] as String
    }

    private void onBranch(String branch) {
        addEnvVar('BRANCH_NAME', branch)
        addEnvVar('BUILD_NUMBER', '87')
    }

    private void onChangeRequest(String id, String target) {
        addEnvVar('BRANCH_NAME', "PR-${id}")
        addEnvVar('CHANGE_ID', id)
        addEnvVar('CHANGE_TARGET', target)
        addEnvVar('BUILD_NUMBER', '87')
    }

    private void onTag(String tag) {
        addEnvVar('BRANCH_NAME', tag)
        addEnvVar('TAG_NAME', tag)
        addEnvVar('BUILD_NUMBER', '87')
    }

}
