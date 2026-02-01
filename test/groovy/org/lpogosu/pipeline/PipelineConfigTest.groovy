package org.lpogosu.pipeline

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy
import static org.assertj.core.api.Assertions.catchThrowableOfType

import org.junit.Test

/**
 * Validation.
 *
 * Every test here describes a Jenkinsfile somebody will write, and checks that the answer
 * arrives in seconds with the field name in it — rather than forty minutes later as a null
 * pointer inside a deploy step.
 */
class PipelineConfigTest {

    private static final Map MINIMAL = [name: 'checkout-api', buildTool: 'maven'].asImmutable()

    @Test
    void aMinimalJenkinsfileGetsUsableDefaults() {
        PipelineConfig config = PipelineConfig.parse(MINIMAL)

        assertThat(config.name).isEqualTo('checkout-api')
        assertThat(config.agentLabel).isEqualTo('linux')
        assertThat(config.skipTests).isFalse()
        assertThat(config.testResults).isEqualTo('**/target/surefire-reports/TEST-*.xml')
        assertThat(config.environments).isEmpty()
        assertThat(config.image).isNull()
    }

    @Test
    void theTestResultPatternFollowsTheBuildTool() {
        assertThat(PipelineConfig.parse(name: 'a-service', buildTool: 'gradle').testResults)
            .isEqualTo('**/build/test-results/test/TEST-*.xml')
        assertThat(PipelineConfig.parse(name: 'a-service', buildTool: 'npm').testResults)
            .isEqualTo('**/reports/junit/*.xml')
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        // The behaviour that makes the difference between fixing a Jenkinsfile in one pass
        // and fixing it in five builds.
        ConfigurationException failure = catchThrowableOfType(
            { PipelineConfig.parse([:]) } as org.assertj.core.api.ThrowableAssert.ThrowingCallable,
            ConfigurationException)

        assertThat(failure.problems).hasSize(2)
        assertThat(failure.message).startsWith('standardPipeline: 2 problems in the Jenkinsfile')
        assertThat(failure.message).contains("'name': required").contains("'buildTool': required")
    }

    @Test
    void aMisspeltOptionIsNamedAndCorrected() {
        assertThatThrownBy { PipelineConfig.parse(MINIMAL + [buildTools: 'maven']) }
            .isInstanceOf(ConfigurationException)
            .hasMessageContaining("unknown option 'buildTools'")
            .hasMessageContaining("Did you mean 'buildTool'?")
    }

    @Test
    void anOptionTooFarFromAnythingKnownGetsTheListInstead() {
        ConfigurationException failure = parseFailure(MINIMAL + [kubernetesNamespace: 'x'])

        assertThat(failure.message).contains("unknown option 'kubernetesNamespace'")
        assertThat(failure.message).doesNotContain('Did you mean')
        assertThat(failure.message).contains('Allowed: agentLabel, buildTool')
    }

    @Test
    void aNameThatCannotBeAReleaseNameIsRejected() {
        ['Checkout API', 'checkout_api', '1checkout', 'ab', 'checkout-'].each { String name ->
            assertThat(parseFailure([name: name, buildTool: 'maven']).message)
                .as(name)
                .contains('is not usable as a release name')
        }
    }

    @Test
    void anUnsupportedBuildToolIsNamedWithWhatIsSupported() {
        ConfigurationException failure = parseFailure(name: 'checkout-api', buildTool: 'sbt')

        assertThat(failure.message).contains("'buildTool': 'sbt' is not supported")
        assertThat(failure.message).contains('Supported: docker, gradle, maven, npm')
    }

    @Test
    void anEnvironmentNameOutsideTheKnownSetIsRejectedAndExpanded() {
        ConfigurationException failure = parseFailure(MINIMAL + [
            image       : [registry: 'registry.example.internal', repository: 'platform/api'],
            environments: [prod: [namespace: 'checkout']],
        ])

        assertThat(failure.message).contains('environments.prod: unknown environment')
        assertThat(failure.message).contains('Known: dev, staging, production')
        assertThat(failure.message).contains("Did you mean 'production'?")
    }

    @Test
    void productionWithoutApproversIsRejected() {
        ConfigurationException failure = parseFailure(MINIMAL + [
            image       : [registry: 'registry.example.internal', repository: 'platform/api'],
            environments: [production: [namespace: 'checkout', kubeconfigCredentialsId: 'kubeconfig']],
        ])

        assertThat(failure.message).contains('environments.production.approvers: required and must not be empty')
    }

    @Test
    void aStagingEnvironmentDoesNotNeedApprovers() {
        PipelineConfig config = PipelineConfig.parse(MINIMAL + [
            image       : [registry: 'registry.example.internal', repository: 'platform/api'],
            environments: [staging: [namespace: 'checkout-staging', kubeconfigCredentialsId: 'kubeconfig']],
        ])

        assertThat(config.environment('staging').requiresApproval()).isFalse()
        assertThat(config.environment('staging').milestone).isEqualTo(2)
    }

    @Test
    void productionAlwaysRequiresApprovalAndTheJenkinsfileCannotSayOtherwise() {
        // There is no option to turn this off, and the parser rejects an attempt to invent
        // one, so the property cannot be quietly removed by the file it protects.
        ConfigurationException failure = parseFailure(MINIMAL + [
            image       : [registry: 'registry.example.internal', repository: 'platform/api'],
            environments: [production: [namespace: 'checkout', kubeconfigCredentialsId: 'k',
                                        approvers: ['leads'], approval: false]],
        ])

        assertThat(failure.message).contains('environments.production.approval: unknown option')

        PipelineConfig config = PipelineConfig.parse(MINIMAL + [
            image       : [registry: 'registry.example.internal', repository: 'platform/api'],
            environments: [production: [namespace: 'checkout', kubeconfigCredentialsId: 'k', approvers: ['leads']]],
        ])
        assertThat(config.environment('production').requiresApproval()).isTrue()
    }

    @Test
    void environmentsWithoutAnImageBlockAreRejected() {
        ConfigurationException failure = parseFailure(MINIMAL + [
            environments: [staging: [namespace: 'checkout-staging', kubeconfigCredentialsId: 'k']],
        ])

        assertThat(failure.message).contains("there is no 'image' block")
    }

    @Test
    void skippingTestsIsIncompatibleWithHavingAProductionEnvironment() {
        ConfigurationException failure = parseFailure(MINIMAL + [
            skipTests   : true,
            image       : [registry: 'registry.example.internal', repository: 'platform/api'],
            environments: [production: [namespace: 'checkout', kubeconfigCredentialsId: 'k', approvers: ['leads']]],
        ])

        assertThat(failure.message).contains("'skipTests' cannot be combined with a production environment")
    }

    @Test
    void skippingTestsIsAllowedForARepositoryThatDeploysNowhere() {
        assertThat(PipelineConfig.parse(MINIMAL + [skipTests: true]).skipTests).isTrue()
    }

    @Test
    void aRegistryWrittenAsAUrlIsRejected() {
        assertThat(parseFailure(MINIMAL + [image: [registry: 'https://registry.example.internal',
                                                   repository: 'platform/api']]).message)
            .contains('is not a registry host')
            .contains('without a scheme')
    }

    @Test
    void aRepositoryNameARegistryWouldRejectIsCaughtHere() {
        assertThat(parseFailure(MINIMAL + [image: [registry: 'registry.example.internal',
                                                   repository: 'Platform/API']]).message)
            .contains('is not a repository name')
    }

    @Test
    void aNamespaceKubernetesWouldRejectIsCaughtHere() {
        assertThat(parseFailure(MINIMAL + [
            image       : [registry: 'registry.example.internal', repository: 'platform/api'],
            environments: [staging: [namespace: 'Checkout_Staging', kubeconfigCredentialsId: 'k']],
        ]).message).contains('is not a Kubernetes namespace')
    }

    @Test
    void aStepThatMustNotBeRetriedRejectsARetryCountWithTheReason() {
        ConfigurationException failure = parseFailure(MINIMAL + [steps: [deploy: [attempts: 3]]])

        assertThat(failure.message).contains("'deploy' cannot be retried automatically")
        assertThat(failure.message).contains('repeat a side effect')
        assertThat(failure.message).contains('raise steps.deploy.timeoutMinutes instead')
    }

    @Test
    void stepOverridesAreRangeCheckedAndSpellChecked() {
        assertThat(parseFailure(MINIMAL + [steps: [build: [timeoutMinutes: 0]]]).message)
            .contains('steps.build.timeoutMinutes: expected an integer between 1 and 720')
        assertThat(parseFailure(MINIMAL + [steps: [build: [timeout: 30]]]).message)
            .contains('steps.build.timeout: unknown option')
        assertThat(parseFailure(MINIMAL + [steps: [compile: [timeoutMinutes: 30]]]).message)
            .contains('steps.compile: unknown step')
    }

    @Test
    void aStepOverrideThatIsAcceptedActuallyTakesEffect() {
        PipelineConfig config = PipelineConfig.parse(MINIMAL + [steps: [build: [timeoutMinutes: 45]]])

        assertThat(config.policy('build').timeoutMinutes).isEqualTo(45)
        assertThat(config.policy('test').timeoutMinutes).as('untouched steps keep their default').isEqualTo(30)
    }

    @Test
    void notificationTargetsAreCheckedForShape() {
        assertThat(parseFailure(MINIMAL + [notify: [slack: 'builds']]).message)
            .contains('should be a channel name starting with #')
        assertThat(parseFailure(MINIMAL + [notify: [email: 'the-team']]).message)
            .contains('is not an email address')
        assertThat(parseFailure(MINIMAL + [notify: [chanel: '#builds']]).message)
            .contains('notify.chanel: unknown option')
    }

    @Test
    void aValueOfTheWrongShapeSaysWhatWasExpected() {
        assertThat(parseFailure(MINIMAL + [image: 'registry.example.internal/platform/api']).message)
            .contains("'image': expected a map, got String")
        assertThat(parseFailure(MINIMAL + [environments: ['staging']]).message)
            .contains("'environments': expected a map")
        assertThat(parseFailure(MINIMAL + [skipTests: 'yes']).message)
            .contains("'skipTests': expected true or false")
    }

    @Test
    void callingTheLibraryWithNothingAtAllIsItsOwnMessage() {
        assertThatThrownBy { PipelineConfig.parse(null) }
            .isInstanceOf(ConfigurationException)
            .hasMessageContaining('called with no configuration at all')
    }

    @Test
    void deployingToAnUndeclaredEnvironmentIsRefusedAtTheDeployStep() {
        PipelineConfig config = PipelineConfig.parse(MINIMAL)

        assertThatThrownBy { config.environment('production') }
            .isInstanceOf(ConfigurationException)
            .hasMessageContaining("declares no such environment")
            .hasMessageContaining('Declared: none')
    }

    private static ConfigurationException parseFailure(Map options) {
        return catchThrowableOfType(
            { PipelineConfig.parse(options) } as org.assertj.core.api.ThrowableAssert.ThrowingCallable,
            ConfigurationException)
    }

}
