package org.lpogosu.pipeline

import java.util.regex.Pattern

import groovy.transform.CompileStatic

/**
 * The Jenkinsfile, parsed and checked.
 *
 * {@link #parse} is called on the Jenkins controller before an executor is allocated, and
 * it either returns a fully valid configuration or throws with every problem it found. The
 * alternative — reading options lazily as each stage needs them — is what produces the
 * failure this library was written to remove: a build that compiles, tests, packages,
 * pushes an image and then dies at the deploy step because {@code approvers} was spelled
 * {@code approver}, forty minutes and one executor later.
 */
@CompileStatic
class PipelineConfig implements Serializable {

    private static final long serialVersionUID = 1L

    static final Set<String> KNOWN_KEYS = ['name', 'buildTool', 'agentLabel', 'skipTests',
                                           'testResults', 'image', 'environments', 'notify', 'steps'].toSet().asImmutable()

    static final Set<String> BUILD_TOOLS = ['maven', 'gradle', 'npm', 'docker'].toSet().asImmutable()

    private static final Set<String> NOTIFY_KEYS = ['slack', 'email'].toSet().asImmutable()

    /** Also used as the Helm release name and as the deploy lock name, hence the DNS shape. */
    private static final Pattern VALID_NAME = Pattern.compile('^[a-z][a-z0-9-]{1,38}[a-z0-9]$')

    private static final Map<String, String> DEFAULT_TEST_RESULTS = [
        'maven' : '**/target/surefire-reports/TEST-*.xml',
        'gradle': '**/build/test-results/test/TEST-*.xml',
        'npm'   : '**/reports/junit/*.xml',
    ].asImmutable()

    String name
    String buildTool
    String agentLabel = 'linux'
    boolean skipTests
    String testResults
    String slackChannel
    String emailTo
    ImageConfig image
    Map<String, EnvironmentConfig> environments = [:]
    private Map<String, StepPolicy> policies = [:]

    /**
     * Validates and builds a configuration.
     *
     * @throws ConfigurationException listing every problem found
     */
    static PipelineConfig parse(Map raw) {
        if (raw == null) {
            throw new ConfigurationException(['standardPipeline was called with no configuration at all'])
        }
        List<String> problems = collectProblems(raw)
        if (problems) {
            throw new ConfigurationException(problems)
        }

        PipelineConfig config = new PipelineConfig(
            name: raw['name'] as String,
            buildTool: raw['buildTool'] as String,
            skipTests: raw['skipTests'] as boolean)
        if (raw['agentLabel']) {
            config.agentLabel = raw['agentLabel'] as String
        }
        config.testResults = (raw['testResults'] ?: DEFAULT_TEST_RESULTS[config.buildTool]) as String
        if (raw['image']) {
            Map imageSettings = (Map) raw['image']
            config.image = new ImageConfig(
                registry: imageSettings['registry'] as String,
                repository: imageSettings['repository'] as String)
            ['credentialsId', 'dockerfile', 'context'].each { String key ->
                if (imageSettings[key]) {
                    config.image.setProperty(key, imageSettings[key] as String)
                }
            }
            if (imageSettings['buildArgs']) {
                config.image.buildArgs = ((Map) imageSettings['buildArgs']).asImmutable()
            }
        }
        Map declaredEnvironments = (Map) (raw['environments'] ?: [:])
        declaredEnvironments.each { Object key, Object settings ->
            config.environments[key as String] = EnvironmentConfig.from(key as String, (Map) settings)
        }
        Map notify = (Map) (raw['notify'] ?: [:])
        config.slackChannel = notify['slack'] as String
        config.emailTo = notify['email'] as String

        Map stepOverrides = (Map) (raw['steps'] ?: [:])
        StepPolicy.KNOWN_STEPS.each { String step ->
            config.policies[step] = StepPolicy.forStep(step, (Map) (stepOverrides[step] ?: [:]))
        }
        return config
    }

    private static List<String> collectProblems(Map raw) {
        List<String> problems = []

        ((raw.keySet() as Set<String>) - KNOWN_KEYS).sort().each { String key ->
            String suggestion = Suggestions.closest(key, KNOWN_KEYS)
            problems << ("unknown option '${key}'" + (suggestion ? ". Did you mean '${suggestion}'?" : '') +
                ' Allowed: ' + KNOWN_KEYS.sort().join(', ')).toString()
        }

        if (!raw['name']) {
            problems << "'name': required, the service name. It becomes the Helm release name and the deploy lock."
        } else if (!VALID_NAME.matcher(raw['name'] as String).matches()) {
            problems << ("'name': '${raw['name']}' is not usable as a release name. " +
                'Lower case letters, digits and dashes, 3 to 40 characters, starting with a letter.').toString()
        }

        if (!raw['buildTool']) {
            problems << "'buildTool': required, one of ${BUILD_TOOLS.sort().join(', ')}".toString()
        } else if (!BUILD_TOOLS.contains(raw['buildTool'] as String)) {
            String suggestion = Suggestions.closest(raw['buildTool'] as String, BUILD_TOOLS)
            problems << ("'buildTool': '${raw['buildTool']}' is not supported" +
                (suggestion ? ". Did you mean '${suggestion}'?" : '') +
                ' Supported: ' + BUILD_TOOLS.sort().join(', ')).toString()
        }

        if (raw.containsKey('skipTests') && !(raw['skipTests'] instanceof Boolean)) {
            problems << "'skipTests': expected true or false, got '${raw['skipTests']}'".toString()
        }

        problems.addAll(imageProblems(raw))
        problems.addAll(environmentProblems(raw))
        problems.addAll(notifyProblems(raw))
        problems.addAll(stepProblems(raw))
        return problems
    }

    private static List<String> imageProblems(Map raw) {
        if (!raw.containsKey('image')) {
            return []
        }
        if (!(raw['image'] instanceof Map)) {
            return ["'image': expected a map, got ${raw['image'] == null ? 'null' : raw['image'].getClass().simpleName}".toString()]
        }
        return ImageConfig.problemsWith((Map) raw['image'])
    }

    private static List<String> environmentProblems(Map raw) {
        List<String> problems = []
        if (raw.containsKey('environments') && !(raw['environments'] instanceof Map)) {
            problems << "'environments': expected a map of environment name to settings".toString()
            return problems
        }
        Map declared = (Map) (raw['environments'] ?: [:])
        declared.each { Object name, Object settings ->
            problems.addAll(EnvironmentConfig.problemsWith(name as String, settings))
        }
        if (declared && !raw['image']) {
            problems << ("'environments' declares ${declared.keySet().join(', ')} but there is no 'image' block. " +
                'This pipeline deploys container images; without one there is nothing to roll out.').toString()
        }
        if (raw['skipTests'] == true && declared.keySet().any { Object it -> EnvironmentConfig.ALWAYS_APPROVED.contains(it as String) }) {
            problems << ("'skipTests' cannot be combined with a production environment. " +
                'Untested code reaching production is exactly the outcome the approval gate is not able to catch.').toString()
        }
        return problems
    }

    private static List<String> notifyProblems(Map raw) {
        if (!raw.containsKey('notify')) {
            return []
        }
        if (!(raw['notify'] instanceof Map)) {
            return ["'notify': expected a map, for example [slack: '#builds']".toString()]
        }
        List<String> problems = []
        Map notify = (Map) raw['notify']
        ((notify.keySet() as Set<String>) - NOTIFY_KEYS).sort().each { String key ->
            problems << "notify.${key}: unknown option. Allowed: ${NOTIFY_KEYS.sort().join(', ')}".toString()
        }
        if (notify['slack'] && !(notify['slack'] as String).startsWith('#')) {
            problems << "notify.slack: '${notify['slack']}' should be a channel name starting with #".toString()
        }
        if (notify['email'] && !(notify['email'] as String).contains('@')) {
            problems << "notify.email: '${notify['email']}' is not an email address".toString()
        }
        return problems
    }

    private static List<String> stepProblems(Map raw) {
        if (!raw.containsKey('steps')) {
            return []
        }
        if (!(raw['steps'] instanceof Map)) {
            return ["'steps': expected a map of step name to overrides, for example [build: [timeoutMinutes: 45]]".toString()]
        }
        List<String> problems = []
        ((Map) raw['steps']).each { Object step, Object overrides ->
            if (!(overrides instanceof Map)) {
                problems << "steps.${step}: expected a map, for example [timeoutMinutes: 45]".toString()
            } else {
                problems.addAll(StepPolicy.problemsWith(step as String, (Map) overrides))
            }
        }
        return problems
    }

    StepPolicy policy(String step) {
        StepPolicy policy = policies[step]
        if (policy == null) {
            throw new IllegalArgumentException("no policy for step '${step}'".toString())
        }
        return policy
    }

    EnvironmentConfig environment(String name) {
        EnvironmentConfig target = environments[name]
        if (target == null) {
            throw new ConfigurationException([
                "this build deploys to '${name}', but the Jenkinsfile declares no such environment. \
Declared: ${environments.keySet().join(', ') ?: 'none'}".toString(),
            ])
        }
        return target
    }

    boolean deploysTo(String name) {
        return environments.containsKey(name)
    }

    /**
     * A readable identity.
     *
     * The default {@code PipelineConfig@6b884d57} is useless in a build log and, because the
     * identity hash changes every run, it also makes the recorded call stacks impossible to
     * compare between runs.
     */
    @Override
    String toString() {
        String targets = environments.keySet() ? environments.keySet().join('+') : 'no deploy'
        return "PipelineConfig(${name}, ${buildTool}, ${image ? image.reference() : 'no image'}, ${targets})"
    }

}
