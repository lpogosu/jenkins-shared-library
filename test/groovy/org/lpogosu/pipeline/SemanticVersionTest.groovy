package org.lpogosu.pipeline

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

import org.junit.Test

class SemanticVersionTest {

    @Test
    void parsesEveryPartOfAFullVersion() {
        SemanticVersion version = SemanticVersion.parse('1.4.2-rc.1+build.507')

        assertThat(version.major).isEqualTo(1)
        assertThat(version.minor).isEqualTo(4)
        assertThat(version.patch).isEqualTo(2)
        assertThat(version.preRelease).isEqualTo('rc.1')
        assertThat(version.buildMetadata).isEqualTo('build.507')
        assertThat(version.toString()).isEqualTo('1.4.2-rc.1+build.507')
    }

    @Test
    void acceptsTheLeadingVThatGitTagsCarry() {
        assertThat(SemanticVersion.parse('v2.0.0').toString()).isEqualTo('2.0.0')
    }

    @Test
    void rejectsThingsThatOnlyLookLikeVersions() {
        ['1.4', '1.4.2.3', '01.4.2', '1.4.2-', 'latest', 'v', '', null].each { String candidate ->
            assertThat(SemanticVersion.isValid(candidate)).as(String.valueOf(candidate)).isFalse()
        }
        assertThatThrownBy { SemanticVersion.parse('1.4') }
            .isInstanceOf(IllegalArgumentException)
            .hasMessageContaining('is not a semantic version')
    }

    @Test
    void comparesNumericallyAndNotAsText() {
        // The reason this class exists: as strings, '1.9.0' sorts after '1.10.0'.
        assertThat(SemanticVersion.parse('1.10.0')).isGreaterThan(SemanticVersion.parse('1.9.0'))
        assertThat(SemanticVersion.parse('1.0.10')).isGreaterThan(SemanticVersion.parse('1.0.9'))
    }

    @Test
    void aPreReleaseRanksBelowTheReleaseItPrecedes() {
        assertThat(SemanticVersion.parse('1.4.2-rc.1')).isLessThan(SemanticVersion.parse('1.4.2'))
        assertThat(SemanticVersion.parse('1.4.2')).isGreaterThan(SemanticVersion.parse('1.4.2-main.99'))
    }

    @Test
    void preReleaseIdentifiersFollowTheSpecOrdering() {
        // The example ordering from semver.org section 11.
        List<String> ascending = ['1.0.0-alpha', '1.0.0-alpha.1', '1.0.0-alpha.beta', '1.0.0-beta',
                                  '1.0.0-beta.2', '1.0.0-beta.11', '1.0.0-rc.1', '1.0.0']
        List<SemanticVersion> shuffled = ascending.collect { SemanticVersion.parse(it) }.reverse()

        assertThat(shuffled.sort().collect { it.toString() }).containsExactlyElementsOf(ascending)
    }

    @Test
    void numericIdentifiersRankBelowAlphanumericOnes() {
        assertThat(SemanticVersion.parse('1.0.0-1')).isLessThan(SemanticVersion.parse('1.0.0-alpha'))
        assertThat(SemanticVersion.parse('1.0.0-2')).isLessThan(SemanticVersion.parse('1.0.0-11'))
    }

    @Test
    void aLongerPreReleaseRanksAboveAPrefixOfItself() {
        assertThat(SemanticVersion.parse('1.0.0-alpha')).isLessThan(SemanticVersion.parse('1.0.0-alpha.1'))
    }

    @Test
    void buildMetadataIsCarriedButNeverCompared() {
        SemanticVersion withMetadata = SemanticVersion.parse('1.4.2+abc')
        SemanticVersion without = SemanticVersion.parse('1.4.2')

        assertThat(withMetadata.compareTo(without)).isZero()
        assertThat(withMetadata).isEqualTo(without)
        assertThat(withMetadata.toString()).isEqualTo('1.4.2+abc')
    }

    @Test
    void nextPatchDropsThePreReleaseAndTheMetadata() {
        assertThat(SemanticVersion.parse('1.4.2-rc.1+abc').nextPatch().toString()).isEqualTo('1.4.3')
    }

    @Test
    void hasPreReleaseDistinguishesAReleaseFromACandidate() {
        assertThat(SemanticVersion.parse('2.0.0').hasPreRelease()).isFalse()
        assertThat(SemanticVersion.parse('2.0.0-rc.1').hasPreRelease()).isTrue()
    }

}
