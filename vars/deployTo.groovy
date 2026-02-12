import org.lpogosu.pipeline.EnvironmentConfig
import org.lpogosu.pipeline.ImageTags
import org.lpogosu.pipeline.PipelineConfig
import org.lpogosu.pipeline.StepPolicy
import org.lpogosu.pipeline.TextTemplate

/**
 * Rolls one image out to one environment.
 *
 * The order of the three things that happen before `helm` runs is deliberate:
 *
 * `milestone` first. A queue of three builds waiting on the same approval will otherwise
 * deploy all three in whatever order the humans click, oldest last. Passing a milestone
 * cancels every earlier build that has not passed it.
 *
 * `input` second, for environments that require it, inside a `timeout` so an unanswered
 * prompt does not hold an executor over a weekend.
 *
 * `lock` third, and only around the rollout itself. Locking before the approval would mean
 * one unanswered prompt blocks every other build's deploy to that environment.
 */
def call(Map args) {
    PipelineConfig config = args.config
    String environmentName = args.environment
    String image = args.image
    String version = args.version
    EnvironmentConfig target = config.environment(environmentName)

    if (!ImageTags.isDigestReference(image)) {
        error("""deployTo(${environmentName}): refusing to deploy '${image}'.
A deploy takes a digest, not a tag. Tags are mutable, including release tags: between the
moment someone approves a rollout and the moment the cluster pulls, a tag can be made to
point somewhere else, and the approval would still read as given.""")
    }

    if (target.requiresApproval()) {
        milestone(ordinal: target.milestone, label: "deploy-${environmentName}")
        StepPolicy approvalPolicy = config.policy('approval')
        timeout(time: approvalPolicy.timeoutMinutes, unit: 'MINUTES') {
            input(message: "Deploy ${config.name} ${version} to ${environmentName}?",
                  ok: 'Deploy',
                  submitter: target.approvers.join(','),
                  submitterParameter: 'approvedBy')
        }
    }

    StepPolicy policy = config.policy('deploy')
    lock(resource: "deploy-${config.name}-${environmentName}") {
        withCredentials([file(credentialsId: target.kubeconfigCredentialsId, variable: 'KUBECONFIG')]) {
            String values = TextTemplate.render(libraryResource('org/lpogosu/pipeline/deploy-values.yaml'), [
                imageRepository: image.substring(0, image.indexOf('@')),
                imageDigest    : image.substring(image.indexOf('@') + 1),
                version        : version,
                commit         : args.commit ?: '',
                environment    : environmentName,
                buildUrl       : env.BUILD_URL ?: '',
            ])
            writeFile(file: 'deploy-values.yaml', text: values)

            withEnv(["RELEASE_NAME=${config.name}",
                     "CHART=${target.chart}",
                     "NAMESPACE=${target.namespace}",
                     "HELM_TIMEOUT=${policy.timeoutMinutes}m",
                     "SERVICE_URL=${target.url ?: ''}"]) {
                timeout(time: policy.timeoutMinutes, unit: 'MINUTES') {
                    // --atomic rolls back on failure. Without it a failed upgrade leaves the
                    // release half-applied and the next deploy starts from a state nobody
                    // described. --wait, because "helm returned 0" otherwise only means the
                    // API server accepted the manifests.
                    sh(label: "helm upgrade ${environmentName}", script: '''
                        helm upgrade --install "$RELEASE_NAME" "$CHART" \\
                            --namespace "$NAMESPACE" \\
                            --create-namespace \\
                            --values deploy-values.yaml \\
                            --atomic --wait --timeout "$HELM_TIMEOUT"
                    '''.stripIndent().trim())
                    smokeCheck(target)
                }
            }
        }
    }
    echo "deployed ${config.name} ${version} to ${environmentName} (${image})"
}

/**
 * One request against the thing that was just deployed.
 *
 * `--atomic --wait` proves the pods reached Ready, which proves the readiness probe passed,
 * which for a lot of services proves that the process started. It says nothing about
 * whether the service is reachable through its ingress, and that is the failure the person
 * approving the rollout cares about.
 */
private void smokeCheck(EnvironmentConfig target) {
    if (!target.url) {
        echo "no url configured for ${target.name}, skipping the post-deploy check"
        return
    }
    sh(label: 'post-deploy check',
       script: 'curl --fail --silent --show-error --max-time 10 "$SERVICE_URL/healthz" > /dev/null')
}
