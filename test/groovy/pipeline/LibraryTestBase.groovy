package pipeline

import static com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration.library
import static com.lesfurets.jenkins.unit.global.lib.ProjectSource.projectSource
import static org.assertj.core.api.Assertions.assertThat

import java.util.regex.Pattern

import com.lesfurets.jenkins.unit.BaseRegressionTest

/**
 * Shared setup for the tests that execute pipeline code.
 *
 * JenkinsPipelineUnit ships mocks for the steps every pipeline uses, but several of them —
 * `node`, `stage`, `withCredentials` — are registered as no-ops that never run the block
 * they are given. A test suite built on those defaults passes without executing a single
 * line of the pipeline, which is the most common way a shared library ends up with green
 * tests that prove nothing. Everything registered below either runs its closure or returns
 * a value the pipeline then has to handle.
 */
abstract class LibraryTestBase extends BaseRegressionTest {

    /** A commit that is 40 hex characters, because scmCheckout checks. */
    static final String COMMIT = 'b17a4d0c9e2f5813a6c04d7e9b25f1c8a3d60e47'

    static final String SHORT_COMMIT = 'b17a4d0'

    static final String DIGEST = 'sha256:' + ('4f2a9c' * 10) + 'abcd'

    static final String LAST_RELEASE_TAG = 'v1.4.2'

    /**
     * The values the credential mocks bind.
     *
     * JenkinsPipelineUnit's own `withCredentials` mock binds each variable to its own name,
     * so `REGISTRY_PASSWORD` holds the string "REGISTRY_PASSWORD". That is harmless and
     * useless: a step that interpolates the password into a shell command looks identical
     * to one that does not. Binding something that could only have come from the credential
     * store is what makes {@code assertNoSecretsLeaked} a real assertion.
     */
    static final Map<String, String> SECRETS = [
        REGISTRY_USER    : 'svc-registry-publisher',
        REGISTRY_PASSWORD: 'gl0bal-registry-token-DO-NOT-LOG',
        KUBECONFIG       : '/run/credentials/kubeconfig-7f21c9',
    ].asImmutable()

    private static final Pattern GIT_REV_PARSE = Pattern.compile('git rev-parse HEAD')
    private static final Pattern GIT_DESCRIBE = Pattern.compile('git describe --tags.*')
    private static final Pattern IMAGETOOLS_INSPECT = Pattern.compile('docker buildx imagetools inspect --format.*')

    @Override
    void setUp() throws Exception {
        // `examples/` is on the list so the files in the README can be executed rather than
        // admired. Documentation that is not run is documentation that is wrong.
        scriptRoots = ['test/jenkins', 'examples'] as String[]
        scriptExtension = 'Jenkinsfile'
        callStackPath = 'test/resources/callstacks/'
        super.setUp()

        helper.registerSharedLibrary(
            library('platform-pipeline')
                .defaultVersion('main')
                // Overrides allowed so that an example pinning `@Library('...@v3')` loads the
                // working tree, which is the version under test.
                .allowOverride(true)
                .implicit(false)
                .targetPath('.')
                .retriever(projectSource('.'))
                .build())

        // JenkinsPipelineUnit pre-parses everything under src/ into its own class loader by
        // default. Gradle has already compiled those same files onto the test classpath, so
        // the pipeline ends up holding a PipelineConfig from one loader while a step's
        // parameter is typed by the other — which fails as "cannot cast PipelineConfig to
        // PipelineConfig". Turning the pre-parse off leaves exactly one definition, the
        // compiled one, which is also the one the unit tests in org.lpogosu.pipeline use.
        helper.libLoader.preloadLibraryClasses = false

        registerBlockSteps()
        registerPluginSteps()
        registerCredentialSteps()
        registerCommandOutput()

        addEnvVar('BUILD_URL', 'https://jenkins.example.internal/job/checkout-api/87/')
        addEnvVar('GIT_URL', 'https://git.example.internal/platform/checkout-api.git')
    }

    /** Steps whose whole purpose is to run the block they wrap. */
    private void registerBlockSteps() {
        helper.registerAllowedMethod('node', [String, Closure], { String label, Closure body ->
            body.delegate = delegate
            helper.callClosure(body)
        })
        helper.registerAllowedMethod('stage', [String, Closure], { String name, Closure body ->
            body.delegate = delegate
            helper.callClosure(body)
        })
        helper.registerAllowedMethod('lock', [Map, Closure], { Map args, Closure body ->
            body.delegate = delegate
            helper.callClosure(body)
        })
        // Jenkins' `error` aborts the build by throwing. The stock mock only flips the
        // status, so a pipeline under test carries on past a failure it would never have
        // survived, and the stage sequence the test asserts is one Jenkins never produces.
        helper.registerAllowedMethod('error', [String], { String message ->
            updateBuildStatus('FAILURE')
            throw new PipelineAborted(message)
        })
    }

    private void registerPluginSteps() {
        helper.registerAllowedMethod('junit', [Map])
        helper.registerAllowedMethod('milestone', [Map])
        helper.registerAllowedMethod('recordIssues', [Map])
        helper.registerAllowedMethod('slackSend', [Map])
        helper.registerAllowedMethod('checkStyle', [Map], { Map args -> args })
        helper.registerAllowedMethod('spotBugs', [Map], { Map args -> args })
        helper.registerAllowedMethod('esLint', [Map], { Map args -> args })
        helper.registerAllowedMethod('input', [Map], { Map args -> [approvedBy: 'platform-leads'] })
        helper.registerAllowedMethod('readJSON', [Map], { Map args -> ['containerimage.digest': DIGEST] })
    }

    private void registerCredentialSteps() {
        helper.registerAllowedMethod('file', [Map], { Map args -> args.variable })
        helper.registerAllowedMethod('withCredentials', [List, Closure], { List requested, Closure body ->
            List names = requested.collectMany { it instanceof List ? it : [it] }
            Map restore = [:]
            names.each { String name ->
                restore[name] = binding.variables.containsKey(name) ? binding.getVariable(name) : null
                binding.setVariable(name, SECRETS[name] ?: "bound-value-of-${name}")
            }
            try {
                body.delegate = delegate
                helper.callClosure(body)
            } finally {
                names.each { String name -> binding.setVariable(name, restore[name]) }
            }
        })
    }

    /** What the shell commands the pipeline runs are made to answer. */
    private void registerCommandOutput() {
        helper.addShMock(GIT_REV_PARSE, COMMIT + '\n', 0)
        helper.addShMock(GIT_DESCRIBE, LAST_RELEASE_TAG + '\n', 0)
        helper.addShMock(IMAGETOOLS_INSPECT, "\"${DIGEST}\"\n".toString(), 0)
    }

    /** The stages the pipeline entered, in order. */
    List<String> stages() {
        return helper.callStack
            .findAll { it.methodName == 'stage' }
            .collect { it.args[0] as String }
    }

    /** Every argument of every recorded call to one step. */
    List<Object> argumentsOf(String step) {
        return helper.callStack.findAll { it.methodName == step }.collect { it.args[0] }
    }

    boolean called(String step) {
        return helper.callStack.any { it.methodName == step }
    }

    /**
     * Fails if any credential value reached the recorded build output.
     *
     * This is the assertion the rest of the suite exists to make possible: everything the
     * pipeline hands to Jenkins goes through the call stack, and Jenkins prints most of it.
     * `CredentialLeakTest` shows the assertion failing against a step that interpolates a
     * password, so it is not passing because it can never fail.
     */
    void assertNoSecretsLeaked() {
        String dump = callStackDump()
        SECRETS.each { String variable, String secret ->
            assertThat(dump)
                .as("the value bound to ${variable} must never reach the build log")
                .doesNotContain(secret)
        }
    }

    /** Thrown by the `error` mock, standing in for Jenkins' AbortException. */
    static class PipelineAborted extends RuntimeException {

        PipelineAborted(String message) {
            super(message)
        }

    }

}
