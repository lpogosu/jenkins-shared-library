import org.lpogosu.pipeline.BranchType
import org.lpogosu.pipeline.BuildContext
import org.lpogosu.pipeline.PipelineConfig
import org.lpogosu.pipeline.TextTemplate

/**
 * Says what happened, to the people who need to know it.
 *
 * A green build on someone's topic branch is not news, and a channel that receives one
 * every few minutes is a channel people mute — which is how the failure notification that
 * mattered gets missed. So topic branches only speak up when they break.
 */
def call(Map args) {
    PipelineConfig config = args.config
    BuildContext context = args.context
    // `result` is null on a build that has not failed yet, so the fallback is what reports
    // success; reading only `currentResult` would miss the FAILURE standardPipeline sets on
    // its way out, because Jenkins has not applied it yet at this point.
    String result = currentBuild.result ?: currentBuild.currentResult ?: 'SUCCESS'

    boolean interesting = result != 'SUCCESS' || context.type != BranchType.FEATURE
    if (!interesting) {
        return
    }

    String message = TextTemplate.render(libraryResource('org/lpogosu/pipeline/build-result.txt'), [
        service     : config.name,
        version     : currentBuild.displayName ?: '',
        result      : result,
        trigger     : context.describe(),
        durationText: currentBuild.durationString ?: 'unknown duration',
        buildUrl    : env.BUILD_URL ?: '',
        buildNumber : context.buildNumber,
        commit      : context.shortCommit ?: 'unknown',
    ])

    if (config.slackChannel) {
        slackSend(channel: config.slackChannel, color: colourFor(result), message: message)
    }
    // Mail is for failures only. A mailbox is a worse place than a channel to keep up with
    // things that went right, and a better one for the thing somebody has to act on.
    if (config.emailTo && result != 'SUCCESS') {
        mail(to: config.emailTo, subject: "[${result}] ${config.name} ${currentBuild.displayName}", body: message)
    }
}

private String colourFor(String result) {
    switch (result) {
        case 'SUCCESS':
            return 'good'
        case 'UNSTABLE':
            return 'warning'
        default:
            return 'danger'
    }
}
