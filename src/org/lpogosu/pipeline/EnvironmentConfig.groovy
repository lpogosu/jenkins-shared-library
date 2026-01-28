package org.lpogosu.pipeline

import java.util.regex.Pattern

import groovy.transform.CompileStatic

/**
 * One deployment target.
 *
 * The set of names is closed. Free-form environment names read as flexibility and behave
 * as a typo surface: {@code prod}, {@code produciton} and {@code production} are three
 * different environments to a map lookup and one environment to everybody else, and the
 * pipeline finds out at the deploy step of a release build.
 */
@CompileStatic
class EnvironmentConfig implements Serializable {

    private static final long serialVersionUID = 1L

    /** Ordered least to most protected; the index is used as the milestone ordinal. */
    static final List<String> KNOWN_ENVIRONMENTS = ['dev', 'staging', 'production'].asImmutable()

    /** Environments that must gate on a human, whatever the Jenkinsfile says. */
    static final Set<String> ALWAYS_APPROVED = ['production'].toSet().asImmutable()

    static final Set<String> KNOWN_KEYS = ['namespace', 'cluster', 'url', 'chart',
                                           'approvers', 'kubeconfigCredentialsId'].toSet().asImmutable()

    private static final Pattern VALID_NAMESPACE = Pattern.compile('^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$')

    String name
    String namespace
    String cluster
    String url
    String chart = 'deploy/chart'
    String kubeconfigCredentialsId
    List<String> approvers = []

    /**
     * Whether a rollout waits for a human.
     *
     * Production always does. This is not configurable, and that is the point: a library
     * whose safety property can be switched off in the Jenkinsfile it protects is
     * documentation, not a control.
     */
    boolean requiresApproval() {
        return ALWAYS_APPROVED.contains(name)
    }

    /**
     * Milestone ordinal, ordered by how protected the environment is.
     *
     * Jenkins cancels an older build that has not yet passed a milestone a newer build has
     * passed. Ordering by environment means a superseded build cannot overtake at the
     * deploy step while an older approval is still pending.
     */
    int getMilestone() {
        return KNOWN_ENVIRONMENTS.indexOf(name) + 1
    }

    static List<String> problemsWith(String name, Object raw) {
        List<String> problems = []
        if (!KNOWN_ENVIRONMENTS.contains(name)) {
            String suggestion = Suggestions.closest(name, KNOWN_ENVIRONMENTS)
            problems << ("environments.${name}: unknown environment. Known: ${KNOWN_ENVIRONMENTS.join(', ')}" +
                (suggestion ? ". Did you mean '${suggestion}'?" : '')).toString()
            return problems
        }
        if (!(raw instanceof Map)) {
            problems << "environments.${name}: expected a map, got ${raw == null ? 'null' : raw.getClass().simpleName}".toString()
            return problems
        }
        Map settings = (Map) raw
        ((settings.keySet() as Set<String>) - KNOWN_KEYS).sort().each { String key ->
            problems << "environments.${name}.${key}: unknown option. Allowed: ${KNOWN_KEYS.sort().join(', ')}".toString()
        }
        if (!settings['namespace']) {
            problems << "environments.${name}.namespace: required".toString()
        } else if (!VALID_NAMESPACE.matcher(settings['namespace'] as String).matches()) {
            problems << ("environments.${name}.namespace: '${settings['namespace']}' is not a Kubernetes " +
                'namespace (lower case alphanumerics and dashes, up to 63 characters)').toString()
        }
        if (!settings['kubeconfigCredentialsId']) {
            problems << "environments.${name}.kubeconfigCredentialsId: required, the id of the kubeconfig file credential".toString()
        }
        if (ALWAYS_APPROVED.contains(name)) {
            Object approvers = settings['approvers']
            if (!(approvers instanceof List) || ((List) approvers).isEmpty()) {
                problems << ("environments.${name}.approvers: required and must not be empty. " +
                    "A rollout to '${name}' waits for one of these Jenkins users or groups.").toString()
            } else if (((List) approvers).any { Object approver -> !(approver instanceof String) || !((String) approver).trim() }) {
                problems << "environments.${name}.approvers: every entry must be a non-empty user or group name".toString()
            }
        }
        if (settings['url'] && !(settings['url'] as String).startsWith('http')) {
            problems << "environments.${name}.url: '${settings['url']}' is not an http(s) URL".toString()
        }
        return problems
    }

    static EnvironmentConfig from(String name, Map settings) {
        EnvironmentConfig config = new EnvironmentConfig(
            name: name,
            namespace: settings['namespace'] as String,
            cluster: settings['cluster'] as String,
            url: settings['url'] as String,
            kubeconfigCredentialsId: settings['kubeconfigCredentialsId'] as String)
        if (settings['chart']) {
            config.chart = settings['chart'] as String
        }
        if (settings['approvers']) {
            config.approvers = (settings['approvers'] as List).collect { Object it -> it.toString() }.asImmutable()
        }
        return config
    }

}
