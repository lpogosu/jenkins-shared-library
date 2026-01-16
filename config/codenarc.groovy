// Ruleset for the library itself: src/ classes and vars/ steps.
//
// Curated rather than "all of them". A Jenkinsfile step is a Groovy script whose whole job
// is to call undeclared methods on an implicit delegate, so a large part of the default
// rulesets is either inapplicable or actively wrong here, and a linter that is wrong often
// enough gets switched off.
ruleset {

    ruleset('rulesets/basic.xml')
    ruleset('rulesets/imports.xml') {
        // Jenkins steps are called by name from the binding, so an import that looks
        // unused to a static reader is often the one making a step resolvable.
        'MisorderedStaticImports' enabled: false
    }
    ruleset('rulesets/unused.xml')
    ruleset('rulesets/exceptions.xml') {
        // Steps rethrow after annotating; the rule cannot tell that apart from swallowing.
        'CatchException' enabled: false
        // standardPipeline catches Throwable to record the failure and rethrows it
        // immediately. Narrowing it would silently skip the notification for an Error, and
        // a build that dies without telling anyone is the outcome this exists to prevent.
        'CatchThrowable' enabled: false
    }
    ruleset('rulesets/groovyism.xml') {
        'GStringExpressionWithinString' enabled: false
    }
    ruleset('rulesets/convention.xml') {
        // These three enforce one particular member ordering. The classes here group a
        // validator next to what it validates instead, which is the ordering somebody
        // reading them needs.
        'PublicMethodsBeforeNonPublicMethods' enabled: false
        'StaticMethodsBeforeInstanceMethods' enabled: false
        'InvertedIfElse' enabled: false
        // A four-element list is not more readable as a ternary.
        'IfStatementCouldBeTernary' enabled: false
        'NoDef' enabled: false
        'MethodReturnTypeRequired' enabled: false
        'CompileStatic' enabled: false
        'ImplicitClosureParameter' enabled: false
        'TrailingComma' enabled: false
        'StaticFieldsBeforeInstanceFields' enabled: false
        'VariableTypeRequired' enabled: false
    }
    ruleset('rulesets/naming.xml') {
        // `call` is the entry point Jenkins invokes on a global step; the file names are
        // the step names, so they are camelCase by design.
        'MethodName' enabled: false
        'ClassName' enabled: false
        'FactoryMethodName' enabled: false
    }
    ruleset('rulesets/size.xml') {
        'AbcMetric' maxMethodAbcScore: 70
        'MethodCount' maxMethods: 30
        'CyclomaticComplexity' maxMethodComplexity: 15
        'MethodSize' maxLines: 80
        'ClassSize' maxLines: 300
        'NestedBlockDepth' maxNestedBlockDepth: 8
        'ParameterCount' maxParameters: 6
        'CrapMetric' enabled: false
    }
    ruleset('rulesets/formatting.xml') {
        'LineLength' length: 140
        'SpaceAroundMapEntryColon' enabled: false
        'Indentation' enabled: false
        'ClosureStatementOnOpeningLineOfMultipleLineClosure' enabled: false
        'BlockEndsWithBlankLine' enabled: false
        'BlockStartsWithBlankLine' enabled: false
    }
    ruleset('rulesets/security.xml') {
        // Config classes are built by Groovy's map constructor, which needs the no-arg one.
        'JavaIoPackageAccess' enabled: false
    }
}
