import org.lpogosu.pipeline.BuildContext
import org.lpogosu.pipeline.ConfigurationException
import org.lpogosu.pipeline.ImageTags
import org.lpogosu.pipeline.PipelineConfig
import org.lpogosu.pipeline.VersionResolver

/**
 * The whole pipeline, from a map of options.
 *
 * Two things happen before `node` is entered, and both are deliberate. The configuration
 * is validated, so a Jenkinsfile with a typo fails in seconds without taking an executor.
 * And the build is classified once, so that every decision below reads a field instead of
 * re-deriving "is this a release" from BRANCH_NAME and reaching its own conclusion.
 */
def call(Map options) {
    PipelineConfig config
    BuildContext context
    try {
        config = PipelineConfig.parse(options)
        context = BuildContext.fromEnvironment([
            BRANCH_NAME  : env.BRANCH_NAME,
            TAG_NAME     : env.TAG_NAME,
            CHANGE_ID    : env.CHANGE_ID,
            CHANGE_TARGET: env.CHANGE_TARGET,
            BUILD_NUMBER : env.BUILD_NUMBER,
        ])
    } catch (ConfigurationException invalid) {
        // The description survives on the build list, which is where the next person looks
        // before they open the log.
        currentBuild.description = 'invalid Jenkinsfile'
        error(invalid.message)
    }

    echo "${config.name}: ${context.describe()}"

    node(config.agentLabel) {
        try {
            if (context.promotion) {
                promote(config, context)
            } else {
                verify(config, context)
            }
        } catch (Throwable failure) {
            // Jenkins does not record the failure until the exception has left the pipeline,
            // so inside the `finally` below currentBuild.result would still be SUCCESS and
            // the notification would cheerfully announce a build that just died.
            currentBuild.result = 'FAILURE'
            throw failure
        } finally {
            notifyBuild(config: config, context: context)
            // The workspace outlives the build unless something deletes it. On a long-lived
            // agent that is how a disk fills up, and how a stale target/ directory ends up
            // in the next build's image.
            cleanWs()
        }
    }
}

/**
 * Topic branches, change requests and trunk: build the code, then decide how far it goes.
 */
private void verify(PipelineConfig config, BuildContext context) {
    Map revision = null
    stage('Checkout') {
        revision = scmCheckout(config: config)
        context.commit = revision.commit
    }

    String version = VersionResolver.resolve(context, revision.lastReleaseTag)
    currentBuild.displayName = "#${currentBuild.number} ${version}"

    stage('Build') {
        buildArtifact(config: config, version: version)
    }

    stage('Verify') {
        Map checks = [failFast: true]
        if (!config.skipTests) {
            checks['Unit tests'] = { runUnitTests(config: config) }
        }
        checks['Static analysis'] = { staticAnalysis(config: config) }
        // failFast because the two checks fail for unrelated reasons: there is nothing to
        // learn from finishing a lint run once the tests are already red.
        parallel(checks)
    }

    if (!config.image || !context.buildingImage) {
        return
    }

    if (!context.publishing) {
        stage('Image (no push)') {
            containerImage(mode: 'build', config: config, context: context)
        }
        return
    }

    String digest = null
    stage('Image') {
        digest = containerImage(mode: 'publish', config: config, context: context, version: version)
    }

    if (config.deploysTo('staging')) {
        stage('Deploy: staging') {
            deployTo(config: config, environment: 'staging', version: version, commit: context.commit,
                     image: ImageTags.digestReference(config.image.reference(), digest))
        }
    }
}

/**
 * Release tags promote, they do not rebuild.
 *
 * Rebuilding from the tagged commit produces a different image: base images move, package
 * indexes move, and a `FROM ...:3.13-slim` resolved today is not the one resolved last
 * week. The thing that was tested on trunk is a specific digest, and the only way to ship
 * that exact thing is to find it and retag it.
 */
private void promote(PipelineConfig config, BuildContext context) {
    if (!config.image) {
        error("release tag ${context.tag} was built, but the Jenkinsfile declares no 'image' block; there is nothing to promote")
    }
    if (!config.deploysTo('production')) {
        error("release tag ${context.tag} was built, but the Jenkinsfile declares no 'production' environment")
    }

    Map revision = null
    stage('Checkout') {
        revision = scmCheckout(config: config)
        context.commit = revision.commit
    }

    String version = VersionResolver.resolve(context, null)
    currentBuild.displayName = "#${currentBuild.number} ${version}"

    String digest = null
    stage('Resolve release candidate') {
        digest = containerImage(mode: 'resolve', config: config, context: context)
    }

    stage('Promote image') {
        containerImage(mode: 'promote', config: config, context: context, digest: digest,
                       tags: ImageTags.forContext(context, version))
    }

    stage('Deploy: production') {
        deployTo(config: config, environment: 'production', version: version, commit: context.commit,
                 image: ImageTags.digestReference(config.image.reference(), digest))
    }
}
