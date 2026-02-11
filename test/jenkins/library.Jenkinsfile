@Library('platform-pipeline') _

// A library that ships to an artifact repository rather than a registry: no image block,
// no environments, so the pipeline stops after the checks.
standardPipeline(
    name: 'billing-clients',
    buildTool: 'gradle',
    notify: [slack: '#billing-builds'],
)
