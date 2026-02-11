@Library('platform-pipeline') _

// Deliberately wrong, in four independent ways, so that the test can check the library
// reports all of them at once rather than the first one it happens to reach.
standardPipeline(
    name: 'Checkout API',
    buildTools: 'maven',
    environments: [
        prod: [namespace: 'checkout'],
    ],
)
