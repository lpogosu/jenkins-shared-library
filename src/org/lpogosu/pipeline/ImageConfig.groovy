package org.lpogosu.pipeline

import java.util.regex.Pattern

import groovy.transform.CompileStatic

/** The {@code image} block of a Jenkinsfile: where the image goes and how it is built. */
@CompileStatic
class ImageConfig implements Serializable {

    private static final long serialVersionUID = 1L

    static final Set<String> KNOWN_KEYS =
        ['registry', 'repository', 'credentialsId', 'dockerfile', 'context', 'buildArgs'].toSet().asImmutable()

    /** A registry host, optionally with a port. No scheme: {@code docker login} does not take one. */
    private static final Pattern VALID_REGISTRY = Pattern.compile('^[a-zA-Z0-9][a-zA-Z0-9.-]*(?::[0-9]{1,5})?$')

    /** The repository name grammar from the OCI distribution spec, without the registry part. */
    private static final Pattern VALID_REPOSITORY =
        Pattern.compile('^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*$')

    String registry
    String repository
    String credentialsId = 'container-registry'
    String dockerfile = 'Dockerfile'
    String context = '.'
    Map buildArgs = [:]

    /** {@code registry/repository} — everything before the tag or digest. */
    String reference() {
        return "${registry}/${repository}".toString()
    }

    static List<String> problemsWith(Map raw) {
        List<String> problems = []
        ((raw.keySet() as Set<String>) - KNOWN_KEYS).sort().each { String key ->
            problems << "image.${key}: unknown option. Allowed: ${KNOWN_KEYS.sort().join(', ')}".toString()
        }
        if (!raw['registry']) {
            problems << 'image.registry: required, for example registry.example.internal'
        } else if (!VALID_REGISTRY.matcher(raw['registry'] as String).matches()) {
            problems << ("image.registry: '${raw['registry']}' is not a registry host. " +
                'Give a host and optional port without a scheme, for example registry.example.internal:5000').toString()
        }
        if (!raw['repository']) {
            problems << 'image.repository: required, for example platform/checkout-api'
        } else if (!VALID_REPOSITORY.matcher(raw['repository'] as String).matches()) {
            problems << ("image.repository: '${raw['repository']}' is not a repository name. " +
                'Lower case, separated by / . _ or -').toString()
        }
        if (raw.containsKey('buildArgs') && !(raw['buildArgs'] instanceof Map)) {
            problems << "image.buildArgs: expected a map of name to value, got ${raw['buildArgs'].getClass().simpleName}".toString()
        }
        return problems
    }

}
