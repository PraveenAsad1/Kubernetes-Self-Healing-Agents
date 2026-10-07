package com.safeheal.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.safeheal")
public class ArchitectureTest {

    @ArchTest
    public static final ArchRule domainShouldNotDependOnSpring = noClasses()
            .that().resideInAnyPackage("..incident..", "..agent..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .allowEmptyShould(true)
            .because("Domain logic must be decoupled from the Spring framework");

    @ArchTest
    public static final ArchRule domainShouldNotDependOnFabric8 = noClasses()
            .that().resideInAnyPackage("..incident..", "..agent..")
            .should().dependOnClassesThat().resideInAPackage("io.fabric8..")
            .allowEmptyShould(true)
            .because("Domain logic must be decoupled from Kubernetes client libraries");
}
