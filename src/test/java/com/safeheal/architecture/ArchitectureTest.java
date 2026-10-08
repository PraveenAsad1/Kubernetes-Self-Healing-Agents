package com.safeheal.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.safeheal", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    @ArchTest
    public static final ArchRule incidentAndAgentMustNotDependOnSpringOrFabric8OrOthers = noClasses()
            .that().resideInAnyPackage("..incident..", "..agent..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "io.fabric8..",
                "..k8s..",
                "..llm..",
                "..api..",
                "..approval..",
                "..audit.."
            )
            .allowEmptyShould(true)
            .because("incident and agent domains must be decoupled from frameworks and infrastructure");

    @ArchTest
    public static final ArchRule policyMustNotDependOnK8sOrLlm = noClasses()
            .that().resideInAnyPackage("..policy..")
            .should().dependOnClassesThat().resideInAnyPackage("..k8s..", "..llm..")
            .allowEmptyShould(true)
            .because("policy must not depend on k8s or llm");
}
