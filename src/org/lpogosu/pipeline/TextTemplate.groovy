package org.lpogosu.pipeline

import java.util.regex.Matcher
import java.util.regex.Pattern

import groovy.transform.CompileStatic

/**
 * Fills {@code ${placeholder}} holes in the templates under {@code resources/}.
 *
 * Groovy's own {@code SimpleTemplateEngine} would do this and more — it evaluates
 * arbitrary Groovy inside the template. Inside a Jenkins shared library that is a
 * sandbox escape waiting for someone to put a user-supplied string in a template, and
 * every template here needs substitution and nothing else.
 *
 * Missing values are an error rather than an empty string. A notification that says
 * "deployed  to " is worse than a build that fails while someone is still looking at it.
 */
@CompileStatic
class TextTemplate implements Serializable {

    private static final long serialVersionUID = 1L

    private static final Pattern PLACEHOLDER = Pattern.compile('\\$\\{([A-Za-z][A-Za-z0-9_]*)\\}')

    static String render(String template, Map values) {
        if (template == null) {
            throw new IllegalArgumentException('template is null; check the path passed to libraryResource')
        }
        Set<String> missing = [] as Set<String>
        StringBuffer rendered = new StringBuffer()
        Matcher matcher = PLACEHOLDER.matcher(template)
        while (matcher.find()) {
            String key = matcher.group(1)
            Object value = values[key]
            if (value == null) {
                missing << key
                value = ''
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value.toString()))
        }
        matcher.appendTail(rendered)
        if (missing) {
            throw new IllegalArgumentException(
                ("template placeholders with no value: ${missing.sort().join(', ')}. " +
                    "Available: ${values.keySet().sort().join(', ')}").toString())
        }
        return rendered.toString()
    }

    /** The placeholders a template needs, for tests that pin a template against its caller. */
    static Set<String> placeholders(String template) {
        Set<String> found = [] as Set<String>
        Matcher matcher = PLACEHOLDER.matcher(template ?: '')
        while (matcher.find()) {
            found << matcher.group(1)
        }
        return found
    }

}
