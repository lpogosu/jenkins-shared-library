import org.lpogosu.pipeline.BuildContext
import org.lpogosu.pipeline.ImageTags
import org.lpogosu.pipeline.PipelineConfig
import org.lpogosu.pipeline.StepPolicy

/**
 * Everything this pipeline does with a container registry, in four modes:
 *
 * <ul>
 *   <li>{@code build}   — build only. Change requests prove the Dockerfile still works
 *       without earning the right to push anything.</li>
 *   <li>{@code publish} — build and push every tag for this build; returns the digest.</li>
 *   <li>{@code resolve} — find the digest already published for this commit.</li>
 *   <li>{@code promote} — point release tags at an existing digest, without rebuilding.</li>
 * </ul>
 *
 * The credential rule for the whole file: a secret is referenced by the name of an
 * environment variable inside a single-quoted script, so the shell expands it and Groovy
 * never sees the value. The moment a script string uses double quotes, the interpolated
 * command is what Jenkins logs, what it stores in the build record, and what it hands to
 * anyone with read access to the job. There is a test that fails if this file stops
 * following the rule.
 *
 * @return the image digest for {@code publish} and {@code resolve}, otherwise null
 */
def call(Map args) {
    PipelineConfig config = args.config
    BuildContext context = args.context
    String mode = args.mode

    if (mode == 'build') {
        StepPolicy policy = config.policy('image')
        timeout(time: policy.timeoutMinutes, unit: 'MINUTES') {
            buildWithoutPushing(config, context)
        }
        return null
    }

    StepPolicy policy = config.policy(mode == 'promote' ? 'promote' : 'image')
    // The digest is assigned rather than returned through the step closures on purpose:
    // whether `retry` and `timeout` pass a block's value back out is a detail of two
    // plugins, and this is not worth depending on it.
    String digest = null
    // The timeout is outermost so that it covers the login, the logout and every retry.
    // Putting it inside the retry would budget each attempt separately, and a step
    // documented as thirty minutes could hold an executor for an hour.
    timeout(time: policy.timeoutMinutes, unit: 'MINUTES') {
        withRegistry(config) {
            retry(policy.attempts) {
                switch (mode) {
                    case 'publish':
                        digest = publish(config, context, args.version)
                        break
                    case 'resolve':
                        digest = resolveDigest(config, context)
                        break
                    case 'promote':
                        promoteTags(config, args.digest, args.tags)
                        break
                    default:
                        error("containerImage: unknown mode '${mode}'")
                }
            }
        }
    }
    return digest
}

/**
 * Opens a registry session for the duration of the closure and closes it afterwards.
 *
 * `DOCKER_CONFIG` points at a per-build directory so that the credential lands in a file
 * this build owns. The default is the agent user's `~/.docker/config.json`, which every
 * later job on that agent can read — a login is not scoped by `withCredentials` unless
 * somebody scopes where it is written.
 */
private void withRegistry(PipelineConfig config, Closure body) {
    withCredentials([usernamePassword(credentialsId: config.image.credentialsId,
                                      usernameVariable: 'REGISTRY_USER',
                                      passwordVariable: 'REGISTRY_PASSWORD')]) {
        withEnv(["REGISTRY_HOST=${config.image.registry}", "DOCKER_CONFIG=${pwd(tmp: true)}/docker"]) {
            try {
                // --password-stdin, not --password: an argument is visible in the process
                // list of the agent for as long as the command runs.
                sh(label: 'registry login',
                   script: 'printf %s "$REGISTRY_PASSWORD" | ' +
                           'docker login --username "$REGISTRY_USER" --password-stdin "$REGISTRY_HOST"')
                body()
            } finally {
                sh(label: 'registry logout', script: 'docker logout "$REGISTRY_HOST" >/dev/null 2>&1 || true')
            }
        }
    }
}

private void buildWithoutPushing(PipelineConfig config, BuildContext context) {
    List references = ImageTags.forContext(context, null).collect { "${config.image.reference()}:${it}" }
    sh(label: 'docker build', script: buildCommand(config, context, references, null, false))
}

private String publish(PipelineConfig config, BuildContext context, String version) {
    List tags = ImageTags.forContext(context, version)
    String metadataFile = 'image-metadata.json'
    List references = tags.collect { "${config.image.reference()}:${it}" }
    sh(label: "docker build and push, ${tags.size()} tags",
       script: buildCommand(config, context, references, metadataFile, true))

    String digest = readDigest(metadataFile)
    echo "published ${config.image.reference()}@${digest} as ${tags.join(', ')}"
    return digest
}

/**
 * Builds the buildx command.
 *
 * Nothing secret is interpolated here, and that is worth being explicit about: the tags,
 * the revision and the version are all values that end up printed in the build log on
 * purpose. Interpolation is fine when the value is meant to be read; it is the one place
 * in this file where a GString is correct.
 */
private String buildCommand(PipelineConfig config, BuildContext context, List references, String metadataFile, boolean push) {
    List parts = ['docker buildx build']
    parts << "--file ${config.image.dockerfile}"
    references.each { parts << "--tag ${it}" }
    // OCI annotations, so that "which commit is this image" can be answered from the
    // image itself. A link to a Jenkins build stops working when the build is rotated out.
    parts << "--label org.opencontainers.image.revision=${context.commit}"
    parts << "--label org.opencontainers.image.source=${env.GIT_URL ?: ''}"
    config.image.buildArgs.each { name, value -> parts << "--build-arg ${name}=${value}" }
    if (metadataFile) {
        parts << "--metadata-file ${metadataFile}"
    }
    parts << (push ? '--push' : '--load')
    parts << config.image.context
    return parts.join(' \\\n    ')
}

/**
 * Reads the digest buildx recorded for the image it just pushed.
 *
 * Asking the daemon afterwards (`docker inspect`, `RepoDigests`) answers a different
 * question — what is in the local cache under that name — and gets it wrong as soon as two
 * builds share an agent.
 */
private String readDigest(String metadataFile) {
    Map metadata = readJSON(file: metadataFile)
    String digest = metadata['containerimage.digest']
    if (!digest) {
        error("containerImage: buildx wrote no containerimage.digest into ${metadataFile}; cannot record what was pushed")
    }
    return digest
}

/**
 * Finds the image a trunk build published for this exact commit.
 */
private String resolveDigest(PipelineConfig config, BuildContext context) {
    String reference = "${config.image.reference()}:${ImageTags.candidateTag(context)}"
    String output = sh(script: "docker buildx imagetools inspect --format '{{json .Manifest.Digest}}' ${reference} 2>/dev/null || true",
                       returnStdout: true, label: 'resolve release candidate').trim()
    String digest = output.replaceAll('"', '')
    if (!ImageTags.VALID_DIGEST.matcher(digest).matches()) {
        error("""containerImage: ${context.tag} points at commit ${context.shortCommit}, which has no published image.
Release tags promote an image that a trunk build already produced; nothing was found at ${reference}.
Tag a commit that has been built on ${BuildContext.TRUNK_BRANCHES.first()}.""")
    }
    return digest
}

/**
 * Adds the release tags to an image that already exists.
 *
 * `imagetools create` copies the manifest, so all the release tags end up naming the exact
 * bytes trunk tested. Rebuilding from the tagged commit would not: base images and package
 * indexes move underneath a Dockerfile, and the result would be a different image wearing
 * the version number of one that was tested.
 */
private void promoteTags(PipelineConfig config, String digest, List tags) {
    String reference = config.image.reference()
    String tagArguments = tags.collect { "--tag ${reference}:${it}" }.join(' ')
    sh(label: "promote image, ${tags.size()} tags", script: "docker buildx imagetools create ${tagArguments} ${reference}@${digest}")
    echo "promoted ${reference}@${digest} to ${tags.join(', ')}"
}
