package org.lpogosu.pipeline

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

import org.junit.Test

/**
 * The tag strategy, one row per case.
 *
 * Written as a table because that is what it is: the rules are only reviewable if all of
 * them are visible at once, and the case somebody forgets — the release candidate — is
 * conspicuous by being the one row that behaves differently.
 */
class ImageTagsTest {

    private static final String COMMIT = 'b17a4d0c9e2f5813a6c04d7e9b25f1c8a3d60e47'

    private static final String DIGEST = 'sha256:' + ('4f2a9c' * 10) + 'abcd'

    @Test
    void tagsForEveryKindOfBuild() {
        List<List> cases = [
            //  branch                  tag           changeId  version              expected tags
            ['feature/checkout-timeout', null,         null,     '1.4.3-feature.4',   []],
            ['PR-42',                    null,         '42',     '1.4.3-pr.42.4',     ['pr-42']],
            ['main',                     null,         null,     '1.4.3-main.87',     ['1.4.3-main.87', 'sha-b17a4d0', 'main']],
            ['v1.4.3',                   'v1.4.3',     null,     '1.4.3',             ['1.4.3', '1.4', '1', 'latest']],
            ['v2.0.0-rc.1',              'v2.0.0-rc.1', null,    '2.0.0-rc.1',        ['2.0.0-rc.1']],
            ['v0.3.0',                   'v0.3.0',     null,     '0.3.0',             ['0.3.0', '0.3', '0', 'latest']],
        ]

        cases.each { List row ->
            BuildContext context = contextFor(row[0] as String, row[1] as String, row[2] as String)
            assertThat(ImageTags.forContext(context, row[3] as String))
                .as("branch=${row[0]} tag=${row[1]} changeId=${row[2]}")
                .isEqualTo(row[4])
        }
    }

    @Test
    void everyTagTheStrategyProducesIsALegalDockerTag() {
        List<String> branches = ['feature/JIRA-12_Fix', 'user/o\'brien/spike', 'release/2.0', '.hidden',
                                 'ветка-с-юникодом', 'a' * 200]

        branches.each { String branch ->
            // The version a topic branch produces is the tag a trunk build would publish if
            // that branch were merged and released, so it has to survive the tag grammar.
            String version = VersionResolver.resolve(contextFor(branch, null, null), 'v1.4.2')
            assertThat(ImageTags.VALID_TAG.matcher(ImageTags.sanitize(version)).matches())
                .as("branch '${branch}' produced version '${version}'")
                .isTrue()
        }
    }

    @Test
    void sanitiseFixesTheThingsARegistryWouldReject() {
        assertThat(ImageTags.sanitize('feature/JIRA-12')).isEqualTo('feature-JIRA-12')
        assertThat(ImageTags.sanitize('.leading-dot')).isEqualTo('leading-dot')
        assertThat(ImageTags.sanitize('trailing-dash-')).isEqualTo('trailing-dash')
        assertThat(ImageTags.sanitize('a' * 200)).hasSize(ImageTags.MAX_TAG_LENGTH)
    }

    @Test
    void sanitiseRefusesRatherThanInventingATag() {
        // Returning something like 'unnamed' here would push an image under a name nobody
        // asked for, and the next build would overwrite it.
        assertThatThrownBy { ImageTags.sanitize('///') }
            .isInstanceOf(IllegalArgumentException)
            .hasMessageContaining('no character that is legal')
    }

    @Test
    void aCandidateTagWithoutACommitIsAProgrammingError() {
        BuildContext context = contextFor('main', null, null)
        context.commit = null

        assertThatThrownBy { ImageTags.candidateTag(context) }
            .isInstanceOf(IllegalStateException)
            .hasMessageContaining('after the checkout step')
    }

    @Test
    void aDigestReferenceIsRefusedUnlessItIsActuallyADigest() {
        assertThat(ImageTags.digestReference('registry.example.internal/platform/api', DIGEST))
            .isEqualTo("registry.example.internal/platform/api@${DIGEST}")

        ['sha256:short', 'sha512:' + ('a' * 64), '1.4.3', '', null].each { String bad ->
            assertThatThrownBy { ImageTags.digestReference('registry.example.internal/platform/api', bad) }
                .as(String.valueOf(bad))
                .isInstanceOf(IllegalArgumentException)
        }
    }

    @Test
    void recognisesADigestReferenceAndRefusesATagThatLooksLikeOne() {
        assertThat(ImageTags.isDigestReference("registry.example.internal/api@${DIGEST}")).isTrue()
        assertThat(ImageTags.isDigestReference('registry.example.internal/api:1.4.3')).isFalse()
        assertThat(ImageTags.isDigestReference('registry.example.internal/api@sha256:nope')).isFalse()
        assertThat(ImageTags.isDigestReference('registry.example.internal:5000/api:1.4.3')).isFalse()
        assertThat(ImageTags.isDigestReference(null)).isFalse()
    }

    private static BuildContext contextFor(String branch, String tag, String changeId) {
        BuildContext context = BuildContext.fromEnvironment(
            [BRANCH_NAME: branch, TAG_NAME: tag, CHANGE_ID: changeId, BUILD_NUMBER: '87'])
        context.commit = COMMIT
        return context
    }

}
