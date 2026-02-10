package org.lpogosu.pipeline

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

import org.junit.Test

class TextTemplateTest {

    @Test
    void substitutesEveryPlaceholder() {
        assertThat(TextTemplate.render('${service} ${version} to ${environment}',
                                       [service: 'checkout-api', version: '1.4.3', environment: 'staging']))
            .isEqualTo('checkout-api 1.4.3 to staging')
    }

    @Test
    void aMissingValueIsAnErrorRatherThanAnEmptyString() {
        // "deployed  to " is worse than a build that fails while someone is still looking.
        assertThatThrownBy { TextTemplate.render('${service} ${version}', [service: 'checkout-api']) }
            .isInstanceOf(IllegalArgumentException)
            .hasMessageContaining('placeholders with no value: version')
            .hasMessageContaining('Available: service')
    }

    @Test
    void everyMissingPlaceholderIsListedNotJustTheFirst() {
        assertThatThrownBy { TextTemplate.render('${a} ${b} ${c}', [b: 'set']) }
            .hasMessageContaining('placeholders with no value: a, c')
    }

    @Test
    void aValueContainingDollarsOrBackslashesIsInsertedLiterally() {
        // Matcher.appendReplacement treats $ and \ as syntax; a password or a Windows path
        // that hit that would be silently mangled.
        assertThat(TextTemplate.render('token=${value}', [value: '$1\\x'])).isEqualTo('token=$1\\x')
    }

    @Test
    void unusedValuesAreAllowed() {
        assertThat(TextTemplate.render('${a}', [a: '1', b: '2'])).isEqualTo('1')
    }

    @Test
    void nonStringValuesAreRenderedNotRejected() {
        assertThat(TextTemplate.render('build ${number}', [number: 87])).isEqualTo('build 87')
    }

    @Test
    void aMissingResourceIsReportedAsSuchRatherThanAsANullPointer() {
        assertThatThrownBy { TextTemplate.render(null, [:]) }
            .isInstanceOf(IllegalArgumentException)
            .hasMessageContaining('check the path passed to libraryResource')
    }

    @Test
    void theCheckedInTemplatesAskForExactlyWhatTheirCallersProvide() {
        // Pins the templates in resources/ against the steps that render them: adding a
        // placeholder to a template without adding the value is otherwise a runtime failure
        // in the notification path, which is the last place anybody looks.
        assertThat(placeholdersOf('resources/org/lpogosu/pipeline/build-result.txt'))
            .containsExactlyInAnyOrder('service', 'version', 'result', 'trigger', 'durationText',
                                       'buildUrl', 'buildNumber', 'commit')
        assertThat(placeholdersOf('resources/org/lpogosu/pipeline/deploy-values.yaml'))
            .containsExactlyInAnyOrder('imageRepository', 'imageDigest', 'version', 'commit',
                                       'buildUrl', 'environment')
    }

    private static Set<String> placeholdersOf(String path) {
        return TextTemplate.placeholders(new File(path).getText('UTF-8'))
    }

}
