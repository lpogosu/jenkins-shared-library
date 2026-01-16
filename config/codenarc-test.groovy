// Ruleset for the tests. Narrower on purpose: the point of linting test code is to catch
// the mistakes that make a test lie — an empty catch that hides a failure, a duplicated
// assertion, an unused fixture — not to make it read like production code.
ruleset {

    ruleset('rulesets/basic.xml') {
        'EmptyCatchBlock' enabled: false
    }
    ruleset('rulesets/imports.xml') {
        'MisorderedStaticImports' enabled: false
    }
    ruleset('rulesets/unused.xml')
    ruleset('rulesets/formatting.xml') {
        'LineLength' length: 160
        'SpaceAroundMapEntryColon' enabled: false
        'Indentation' enabled: false
        'ClosureStatementOnOpeningLineOfMultipleLineClosure' enabled: false
        'BlockEndsWithBlankLine' enabled: false
        'BlockStartsWithBlankLine' enabled: false
    }
}
