package org.lpogosu.pipeline

import static org.assertj.core.api.Assertions.assertThat

import org.junit.Test

class SuggestionsTest {

    private static final List<String> KEYS = ['name', 'buildTool', 'agentLabel', 'environments', 'image'].asImmutable()

    @Test
    void correctsATypo() {
        assertThat(Suggestions.closest('buildTools', KEYS)).isEqualTo('buildTool')
        assertThat(Suggestions.closest('enviroments', KEYS)).isEqualTo('environments')
        assertThat(Suggestions.closest('Name', KEYS)).isEqualTo('name')
    }

    @Test
    void expandsAnAbbreviationEvenThoughItIsFarInEditDistance() {
        assertThat(Suggestions.closest('prod', ['dev', 'staging', 'production'])).isEqualTo('production')
        assertThat(Suggestions.closest('environ', KEYS)).isEqualTo('environments')
    }

    @Test
    void staysQuietWhenNothingIsClose() {
        assertThat(Suggestions.closest('kubernetesNamespace', KEYS)).isNull()
        assertThat(Suggestions.closest('', KEYS)).isNull()
    }

    @Test
    void staysQuietWhenAnAbbreviationIsAmbiguous() {
        // 'st' expands to both; guessing one of them would send somebody the wrong way.
        assertThat(Suggestions.closest('sta', ['staging', 'standby'])).isNull()
    }

    @Test
    void staysQuietWhenAnAbbreviationIsTooShortToMeanAnything() {
        assertThat(Suggestions.closest('en', KEYS)).isNull()
    }

    @Test
    void picksTheSameCandidateEveryTimeWhenTwoAreEquallyClose() {
        // The candidate collections are sets and maps; iteration order is not a contract.
        List<String> candidates = ['bat', 'cat', 'mat']
        assertThat(Suggestions.closest('hat', candidates)).isEqualTo('bat')
        assertThat(Suggestions.closest('hat', candidates.reverse())).isEqualTo('bat')
    }

    @Test
    void measuresEditDistanceCorrectly() {
        assertThat(Suggestions.editDistance('kitten', 'sitting')).isEqualTo(3)
        assertThat(Suggestions.editDistance('', 'abc')).isEqualTo(3)
        assertThat(Suggestions.editDistance('same', 'same')).isZero()
    }

}
