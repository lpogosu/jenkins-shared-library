@Library('platform-pipeline') _

standardPipeline(
    name: 'checkout-api',
    buildTool: 'maven',
    agentLabel: 'linux && docker',
    image: [
        registry     : 'registry.example.internal',
        repository   : 'platform/checkout-api',
        credentialsId: 'registry-publisher',
    ],
    environments: [
        staging   : [
            namespace              : 'checkout-staging',
            url                    : 'https://checkout.staging.example',
            kubeconfigCredentialsId: 'kubeconfig-staging',
        ],
        production: [
            namespace              : 'checkout',
            url                    : 'https://checkout.example',
            kubeconfigCredentialsId: 'kubeconfig-production',
            approvers              : ['platform-leads', 'checkout-oncall'],
        ],
    ],
    notify: [slack: '#checkout-builds', email: 'checkout-team@example.com'],
)
