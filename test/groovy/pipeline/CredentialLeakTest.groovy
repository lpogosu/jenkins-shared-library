package pipeline

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

import java.util.regex.Pattern

import org.junit.Before
import org.junit.Test

/**
 * The one failure a Jenkins pipeline cannot take back.
 *
 * A password interpolated into a shell command ends up in the console log, in the build
 * record on disk, and in whatever ships those logs somewhere else. Jenkins masks the value
 * of a bound credential in the log, but only the exact string — a base64'd copy, a URL with
 * the token in it, or a token that happens to be split across a line boundary all go
 * straight through. Rotating afterwards is the only remedy, and it starts with somebody
 * noticing.
 *
 * So the assertion is made mechanically, over the full recorded output of a real run.
 */
class CredentialLeakTest extends LibraryTestBase {

    @Override
    @Before
    void setUp() throws Exception {
        super.setUp()
        addEnvVar('BUILD_NUMBER', '87')
    }

    @Test
    void noCredentialValueSurvivesATrunkBuild() {
        addEnvVar('BRANCH_NAME', 'main')

        runScript('standard.Jenkinsfile')

        // Registry credentials in the image stage, a kubeconfig file in the deploy stage.
        assertThat(called('withCredentials')).isTrue()
        assertNoSecretsLeaked()
    }

    @Test
    void noCredentialValueSurvivesAReleasePromotion() {
        addEnvVar('BRANCH_NAME', 'v1.5.0')
        addEnvVar('TAG_NAME', 'v1.5.0')

        runScript('standard.Jenkinsfile')

        assertNoSecretsLeaked()
    }

    @Test
    void noCredentialValueSurvivesAFailedBuildEither() {
        addEnvVar('BRANCH_NAME', 'main')
        helper.addShMock(Pattern.compile('docker buildx build.*', Pattern.DOTALL), 'denied: requested access to the resource is denied', 1)

        assertThatThrownBy { runScript('standard.Jenkinsfile') }.isInstanceOf(Exception)

        // A failing step prints more, not less. The notification path runs here too.
        assertNoSecretsLeaked()
    }

    @Test
    void theRegistryPasswordIsNeverPassedAsACommandArgument() {
        addEnvVar('BRANCH_NAME', 'main')

        runScript('standard.Jenkinsfile')

        String login = helper.callStack
            .findAll { it.methodName == 'sh' }
            .collect { (it.args[0] as Map)['script'] as String }
            .find { it.contains('docker login') }
        assertThat(login).contains('--password-stdin')
        assertThat(login)
            .as('an argument is readable in the agent process list for as long as the command runs')
            .doesNotContain('--password ')
            .doesNotContain('-p ')
    }

    /**
     * Proves the assertion above can fail.
     *
     * Without this, {@code assertNoSecretsLeaked} would keep passing if the credential mock
     * ever stopped binding a real value — a test that cannot fail is not evidence of
     * anything. This runs the mistake the rest of the library is written to avoid and
     * checks that the detector catches it.
     */
    @Test
    void theLeakDetectorCatchesAnInterpolatedPassword() {
        runInlineScript('''
            withCredentials([usernamePassword(credentialsId: 'registry-publisher',
                                              usernameVariable: 'REGISTRY_USER',
                                              passwordVariable: 'REGISTRY_PASSWORD')]) {
                sh(label: 'login', script: "docker login -u ${REGISTRY_USER} -p ${REGISTRY_PASSWORD} registry.example.internal")
            }
        ''')

        assertThatThrownBy { assertNoSecretsLeaked() }
            .isInstanceOf(AssertionError)
            .hasMessageContaining('REGISTRY_PASSWORD')
    }

}
