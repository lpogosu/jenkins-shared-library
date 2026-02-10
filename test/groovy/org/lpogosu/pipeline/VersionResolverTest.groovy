package org.lpogosu.pipeline

import static org.assertj.core.api.Assertions.assertThat

import org.junit.Test

class VersionResolverTest {

    @Test
    void aReleaseTagIsItsOwnVersion() {
        assertThat(resolve('v1.5.0', 'v1.5.0', null, 12, 'v1.4.2')).isEqualTo('1.5.0')
        assertThat(resolve('v2.0.0-rc.1', 'v2.0.0-rc.1', null, 12, 'v1.4.2')).isEqualTo('2.0.0-rc.1')
    }

    @Test
    void trunkAndTopicBuildsPreReleaseTheNextPatch() {
        assertThat(resolve('main', null, null, 87, 'v1.4.2')).isEqualTo('1.4.3-main.87')
        assertThat(resolve('PR-42', null, '42', 3, 'v1.4.2')).isEqualTo('1.4.3-pr.42.3')
        assertThat(resolve('feature/checkout-timeout', null, null, 4, 'v1.4.2'))
            .isEqualTo('1.4.3-feature-checkout-timeout.4')
    }

    @Test
    void everyDevelopmentBuildSortsBelowTheReleaseItLeadsTo() {
        // The property the whole scheme exists for. Suffixing the *previous* release —
        // 1.4.2-main.87, which is the shape most pipelines produce — would instead put every
        // development build below a version that already shipped.
        SemanticVersion trunk = SemanticVersion.parse(resolve('main', null, null, 87, 'v1.4.2'))
        SemanticVersion topic = SemanticVersion.parse(resolve('feature/x', null, null, 4, 'v1.4.2'))

        assertThat(trunk).isGreaterThan(SemanticVersion.parse('1.4.2'))
        assertThat(trunk).isLessThan(SemanticVersion.parse('1.4.3'))
        assertThat(topic).isLessThan(SemanticVersion.parse('1.4.3'))
    }

    @Test
    void laterBuildsOfTheSameBranchSortLater() {
        SemanticVersion earlier = SemanticVersion.parse(resolve('main', null, null, 9, 'v1.4.2'))
        SemanticVersion later = SemanticVersion.parse(resolve('main', null, null, 10, 'v1.4.2'))

        assertThat(later).isGreaterThan(earlier)
    }

    @Test
    void aRepositoryWithNoReleaseYetStartsAtTheFirstPatch() {
        assertThat(resolve('main', null, null, 1, null)).isEqualTo('0.0.1-main.1')
        assertThat(resolve('main', null, null, 1, '')).isEqualTo('0.0.1-main.1')
    }

    @Test
    void aTagThatIsNotAVersionIsIgnoredRatherThanTrusted() {
        // `git describe --match "v[0-9]*"` can still return something like v2-beta.
        assertThat(resolve('main', null, null, 5, 'v2-beta')).isEqualTo('0.0.1-main.5')
    }

    @Test
    void branchNamesAreReducedToOneLegalIdentifier() {
        Map<String, String> expected = [
            'feature/JIRA-12'      : 'feature-jira-12',
            'user/name/spike'      : 'user-name-spike',
            'release_2.0'          : 'release-2-0',
            '--weird--'            : 'weird',
            'ветка'                : 'branch',
            '007'                  : 'b007',
            '42'                   : '42',
        ]

        expected.each { String branch, String identifier ->
            assertThat(VersionResolver.preReleaseIdentifier(branch)).as(branch).isEqualTo(identifier)
        }
    }

    @Test
    void hostileBranchNamesStillProduceValidSemver() {
        List<String> branches = ['feature/JIRA-12', 'ветка', '007', '-', 'a' * 300,
                                 'user/o\'brien/spike', 'release/2.0.0+build']

        branches.each { String branch ->
            String version = resolve(branch, null, null, 7, 'v1.4.2')
            assertThat(SemanticVersion.isValid(version)).as("branch '${branch}' -> '${version}'").isTrue()
        }
    }

    @Test
    void aLongBranchNameIsTruncatedWithoutLeavingATrailingSeparator() {
        String identifier = VersionResolver.preReleaseIdentifier('feature/' + ('long-' * 20))

        assertThat(identifier).doesNotEndWith('-')
        assertThat(identifier.length()).isLessThanOrEqualTo(40)
    }

    private static String resolve(String branch, String tag, String changeId, int buildNumber, String lastTag) {
        BuildContext context = BuildContext.fromEnvironment(
            [BRANCH_NAME: branch, TAG_NAME: tag, CHANGE_ID: changeId, BUILD_NUMBER: String.valueOf(buildNumber)])
        return VersionResolver.resolve(context, lastTag)
    }

}
