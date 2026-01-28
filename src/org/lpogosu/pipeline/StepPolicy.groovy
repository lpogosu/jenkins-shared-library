package org.lpogosu.pipeline

import groovy.transform.CompileStatic

/**
 * How long a step may take and how many times it may be attempted.
 *
 * Two rules are enforced here rather than left to the Jenkinsfile:
 *
 * Every step has a timeout. A Jenkins step without one waits forever — a `docker push`
 * against a registry that accepted the connection and then stopped answering holds an
 * executor until someone notices, which on a busy controller is the next morning.
 *
 * Some steps get exactly one attempt regardless of what the Jenkinsfile asks for. A retry
 * is only safe when the first attempt either did nothing or did the whole thing; a rollout
 * that timed out half-applied satisfies neither.
 */
@CompileStatic
class StepPolicy implements Serializable {

    private static final long serialVersionUID = 1L

    static final int MAX_TIMEOUT_MINUTES = 720
    static final int MAX_ATTEMPTS = 5

    /**
     * Steps that may never be retried automatically.
     *
     * `deploy` because a timed-out `helm upgrade` may still be converging, and a second
     * one racing it produces a state neither release describes. `promote` because it moves
     * release tags, and repeating it after a partial failure can leave `1.4` and `latest`
     * pointing at different digests. `approval` because re-prompting a human who already
     * declined is not a retry, it is nagging until someone clicks yes.
     */
    static final Set<String> AT_MOST_ONCE = ['deploy', 'promote', 'approval'].toSet().asImmutable()

    private static final Map<String, List<Integer>> DEFAULTS = [
        // step      : [attempts, timeoutMinutes]
        'checkout'   : [3, 10],
        'build'      : [1, 30],
        'test'       : [1, 30],
        'analysis'   : [1, 20],
        'image'      : [2, 30],
        'promote'    : [1, 10],
        'approval'   : [1, 720],
        'deploy'     : [1, 15],
    ].asImmutable()

    static final Set<String> KNOWN_STEPS = DEFAULTS.keySet().asImmutable()

    final String step
    final int attempts
    final int timeoutMinutes

    private StepPolicy(String step, int attempts, int timeoutMinutes) {
        this.step = step
        this.attempts = attempts
        this.timeoutMinutes = timeoutMinutes
    }

    /**
     * Builds the policy for one step.
     *
     * @param step      one of {@link #KNOWN_STEPS}
     * @param overrides {@code [attempts: n, timeoutMinutes: n]}, either key optional
     */
    static StepPolicy forStep(String step, Map overrides = [:]) {
        List<Integer> defaults = DEFAULTS[step]
        if (defaults == null) {
            throw new IllegalArgumentException("unknown step '${step}'; known steps: ${KNOWN_STEPS.sort().join(', ')}".toString())
        }
        int attempts = (overrides['attempts'] ?: defaults[0]) as int
        int timeout = (overrides['timeoutMinutes'] ?: defaults[1]) as int
        // PipelineConfig rejects this with a readable message long before a node is
        // allocated. This is the invariant behind that message: no caller gets to hand a
        // retry count to a step that must not be retried, however it reached this method.
        if (attempts > 1 && AT_MOST_ONCE.contains(step)) {
            throw new IllegalArgumentException("step '${step}' must not be retried (asked for ${attempts} attempts)".toString())
        }
        return new StepPolicy(step, attempts, timeout)
    }

    /**
     * Checks one override block. Returns the problems found so that
     * {@link PipelineConfig} can report all of them at once.
     */
    static List<String> problemsWith(String step, Map overrides) {
        List<String> problems = []
        if (!KNOWN_STEPS.contains(step)) {
            problems << "steps.${step}: unknown step. Known steps: ${KNOWN_STEPS.sort().join(', ')}".toString()
            return problems
        }
        Set<String> unknownKeys = (overrides.keySet() as Set<String>) - ['attempts', 'timeoutMinutes'].toSet()
        unknownKeys.sort().each { String key ->
            problems << "steps.${step}.${key}: unknown option. Allowed: attempts, timeoutMinutes".toString()
        }
        if (overrides.containsKey('attempts')) {
            Object value = overrides['attempts']
            if (!(value instanceof Integer) || (value as int) < 1 || (value as int) > MAX_ATTEMPTS) {
                problems << "steps.${step}.attempts: expected an integer between 1 and ${MAX_ATTEMPTS}, got '${value}'".toString()
            } else if (AT_MOST_ONCE.contains(step) && (value as int) > 1) {
                problems << ("steps.${step}.attempts: '${step}' cannot be retried automatically. " +
                    'Retrying it would repeat a side effect the first attempt may already have applied; ' +
                    'raise steps.' + step + '.timeoutMinutes instead, or re-run the build.').toString()
            }
        }
        if (overrides.containsKey('timeoutMinutes')) {
            Object value = overrides['timeoutMinutes']
            if (!(value instanceof Integer) || (value as int) < 1 || (value as int) > MAX_TIMEOUT_MINUTES) {
                problems << ("steps.${step}.timeoutMinutes: expected an integer between 1 and " +
                    "${MAX_TIMEOUT_MINUTES}, got '${value}'").toString()
            }
        }
        return problems
    }

    @Override
    String toString() {
        return "${step}(attempts=${attempts}, timeout=${timeoutMinutes}m)"
    }

}
