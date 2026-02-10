package pipeline

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

import org.junit.Before
import org.junit.Test

/**
 * The approval gate and the digest rule.
 *
 * Both are properties somebody will eventually want to switch off for a good reason on a
 * bad day, which is exactly why they are asserted rather than documented.
 */
class DeployToTest extends LibraryTestBase {

    @Override
    @Before
    void setUp() throws Exception {
        super.setUp()
        addEnvVar('BUILD_NUMBER', '87')
    }

    @Test
    void stagingRollsOutWithoutAskingAnybody() {
        addEnvVar('BRANCH_NAME', 'main')

        runScript('standard.Jenkinsfile')

        assertThat(stages()).contains('Deploy: staging')
        assertThat(called('input'))
            .as('an approval on every staging deploy is an approval nobody reads')
            .isFalse()
        assertThat(called('lock')).isTrue()
    }

    @Test
    void productionWaitsForOneOfTheNamedApprovers() {
        onReleaseTag()

        runScript('standard.Jenkinsfile')

        Map prompt = argumentsOf('input').first() as Map
        assertThat(prompt['submitter']).isEqualTo('platform-leads,checkout-oncall')
        assertThat(prompt['message'] as String).isEqualTo('Deploy checkout-api 1.5.0 to production?')
        assertThat(prompt['submitterParameter'])
            .as('who approved is part of the record, not something to reconstruct later')
            .isEqualTo('approvedBy')
    }

    @Test
    void supersededBuildsAreCancelledBeforeTheApprovalIsAskedFor() {
        onReleaseTag()

        runScript('standard.Jenkinsfile')

        assertThat(indexOfCall('milestone'))
            .as('a queue of builds waiting on one approval must not deploy oldest-last')
            .isLessThan(indexOfCall('input'))
        assertThat((argumentsOf('milestone').first() as Map)['ordinal']).isEqualTo(3)
    }

    @Test
    void anUnansweredApprovalDoesNotHoldAnExecutorForever() {
        onReleaseTag()

        runScript('standard.Jenkinsfile')

        List<Integer> timeouts = argumentsOf('timeout').collect { (it as Map)['time'] as Integer }
        assertThat(timeouts).contains(720)
    }

    @Test
    void theLockIsTakenAfterTheApprovalAndNotBefore() {
        onReleaseTag()

        runScript('standard.Jenkinsfile')

        assertThat(indexOfCall('input'))
            .as('locking first would let one unanswered prompt block every other deploy')
            .isLessThan(indexOfCall('lock'))
    }

    @Test
    void theRolloutReferencesTheDigestAndNotAnyTag() {
        addEnvVar('BRANCH_NAME', 'main')

        runScript('standard.Jenkinsfile')

        String values = (argumentsOf('writeFile').first() as Map)['text'] as String
        assertThat(values).contains("digest: ${DIGEST}")
        assertThat(values).contains('repository: registry.example.internal/platform/checkout-api')
        assertThat(values).contains('org.opencontainers.image.revision: "' + COMMIT + '"')
    }

    @Test
    void helmRollsBackRatherThanLeavingAHalfAppliedRelease() {
        addEnvVar('BRANCH_NAME', 'main')

        runScript('standard.Jenkinsfile')

        String helm = shellScripts().find { it.contains('helm upgrade') }
        assertThat(helm).contains('--atomic').contains('--wait')
        assertThat(shellScripts().any { it.contains('curl --fail') })
            .as('ready pods prove the process started, not that the service answers')
            .isTrue()
    }

    @Test
    void aTagInsteadOfADigestIsRefusedBeforeAnythingIsApplied() {
        addEnvVar('BRANCH_NAME', 'main')

        assertThatThrownBy {
            runInlineScript('''
                @Library('platform-pipeline') _
                import org.lpogosu.pipeline.PipelineConfig

                deployTo(config: PipelineConfig.parse([
                            name        : 'checkout-api',
                            buildTool   : 'maven',
                            image       : [registry: 'registry.example.internal', repository: 'platform/checkout-api'],
                            environments: [staging: [namespace: 'checkout-staging',
                                                     kubeconfigCredentialsId: 'kubeconfig-staging']],
                         ]),
                         environment: 'staging',
                         version: '1.4.3',
                         image: 'registry.example.internal/platform/checkout-api:1.4.3')
            ''')
        }
            .isInstanceOf(PipelineAborted)
            .hasMessageContaining('A deploy takes a digest, not a tag')

        assertThat(shellScripts().any { it.contains('helm upgrade') })
            .as('the refusal comes before anything touches the cluster')
            .isFalse()
    }

    private void onReleaseTag() {
        addEnvVar('BRANCH_NAME', 'v1.5.0')
        addEnvVar('TAG_NAME', 'v1.5.0')
    }

    private List<String> shellScripts() {
        return helper.callStack
            .findAll { it.methodName == 'sh' }
            .collect { it.args[0] instanceof Map ? (it.args[0] as Map)['script'] as String : it.args[0] as String }
    }

    private int indexOfCall(String step) {
        return helper.callStack.findIndexOf { it.methodName == step }
    }

}
