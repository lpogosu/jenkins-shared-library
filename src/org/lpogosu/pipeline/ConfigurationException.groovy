package org.lpogosu.pipeline

import groovy.transform.CompileStatic

/**
 * Raised when a Jenkinsfile asks for something the library cannot do.
 *
 * Carries every problem found, not the first one. Fixing a Jenkinsfile one error per
 * five-minute build round trip is how people learn to stop using the library.
 */
@CompileStatic
class ConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L

    final List<String> problems

    ConfigurationException(List<String> problems) {
        super(render(problems))
        this.problems = problems.asImmutable()
    }

    private static String render(List<String> problems) {
        String heading = problems.size() == 1 ? 'standardPipeline: 1 problem in the Jenkinsfile'
                                              : "standardPipeline: ${problems.size()} problems in the Jenkinsfile"
        return ([heading.toString()] + problems.collect { String problem -> "  - ${problem}".toString() }).join('\n')
    }

}
