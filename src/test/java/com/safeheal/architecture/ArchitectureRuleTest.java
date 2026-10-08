package com.safeheal.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class ArchitectureRuleTest {

    @Test
    void testIncidentRuleViolation() {
        JavaClasses importedClasses = new ClassFileImporter().importClasses(com.safeheal.fixtures.incident.ViolatingIncidentClass.class);
        assertThrows(AssertionError.class, () -> ArchitectureTest.incidentAndAgentMustNotDependOnSpringOrFabric8OrOthers.check(importedClasses));
    }

    @Test
    void testAgentRuleViolation() {
        JavaClasses importedClasses = new ClassFileImporter().importClasses(com.safeheal.fixtures.agent.ViolatingAgentClass.class);
        assertThrows(AssertionError.class, () -> ArchitectureTest.incidentAndAgentMustNotDependOnSpringOrFabric8OrOthers.check(importedClasses));
    }

    @Test
    void testPolicyRuleViolation() {
        JavaClasses importedClasses = new ClassFileImporter().importClasses(com.safeheal.fixtures.policy.ViolatingPolicyClass.class);
        assertThrows(AssertionError.class, () -> ArchitectureTest.policyMustNotDependOnK8sOrLlm.check(importedClasses));
    }
}
