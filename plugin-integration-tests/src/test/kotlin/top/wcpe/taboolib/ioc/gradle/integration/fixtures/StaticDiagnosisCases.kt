package top.wcpe.taboolib.ioc.gradle.integration.fixtures

internal data class StaticDiagnosisCase(
    val name: String,
    val ownerSimpleName: String,
    val expectedRule: String? = null,
    val expectedSeverity: String? = null,
)

internal object StaticDiagnosisCases {

    fun cases(): List<StaticDiagnosisCase> = buildList {
        repeat(10) { i ->
            add(valid("valid constructor injection #$i", "ValidConstructorConsumer$i"))
            add(valid("valid field injection #$i", "ValidFieldConsumer$i"))
            add(valid("valid method injection #$i", "ValidMethodConsumer$i"))
            add(valid("valid named injection #$i", "ValidNamedConsumer$i"))
            add(valid("valid primary injection #$i", "ValidPrimaryConsumer$i"))
            add(valid("valid generic injection #$i", "ValidGenericConsumer$i"))
        }
        repeat(5) { i ->
            add(error("missing bean constructor dependency #$i", "MissingBeanConsumer$i", "missing-bean"))
            add(error("named bean not found #$i", "NamedNotFoundConsumer$i", "named-bean-not-found"))
            add(error("named bean type mismatch #$i", "NamedTypeMismatchConsumer$i", "named-bean-type-mismatch"))
            add(error("multiple primary beans #$i", "MultiplePrimaryConsumer$i", "multiple-primary-beans"))
            add(warning("multiple candidates without qualifier #$i", "MultipleCandidatesConsumer$i", "multiple-candidates-unqualified"))
            add(error("missing inject annotation #$i", "MissingInjectConsumer$i", "missing-inject-annotation"))
            add(warning("component scan may exclude candidate #$i", "ComponentScanExcludedConsumer$i", "component-scan-may-exclude"))
            add(warning("conditional bean only #$i", "ConditionalOnlyConsumer$i", "conditional-bean-only"))
            add(error("disabled conditional bean is missing #$i", "DisabledConditionalConsumer$i", "missing-bean"))
            add(warning("optional runtime manual bean only #$i", "RuntimeManualConsumer$i", "runtime-manual-bean-only"))
            add(error("constructor circular dependency #$i", "CycleA$i", "circular-dependency-detected"))
            add(warning("refresh scope missing predestroy #$i", "RefreshResourceBean$i", "refresh-scope-missing-predestroy"))
            add(info("thread scope usage warning #$i", "ThreadScopedBean$i", "thread-scope-usage-warning"))
        }
    }

    fun forbiddenComponentCases(): List<StaticDiagnosisCase> = (0 until 5).map { i ->
        warning("forbidden component annotation #$i", "ForbiddenComponent$i", "forbidden-component-annotation")
    }

    private fun valid(name: String, owner: String) = StaticDiagnosisCase(name, owner)

    private fun error(name: String, owner: String, rule: String) = StaticDiagnosisCase(name, owner, rule, "error")

    private fun warning(name: String, owner: String, rule: String) = StaticDiagnosisCase(name, owner, rule, "warning")

    private fun info(name: String, owner: String, rule: String) = StaticDiagnosisCase(name, owner, rule, "info")
}
