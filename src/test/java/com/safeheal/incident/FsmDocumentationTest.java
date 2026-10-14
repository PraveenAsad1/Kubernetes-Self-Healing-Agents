package com.safeheal.incident;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FsmDocumentationTest {
    @Test
    void transitionTableMatchesDocs() throws Exception {
        Path fsmDoc = Paths.get("docs/FSM.md");
        StringBuilder expected = new StringBuilder();
        expected.append("# FSM Transition Table\n\n");
        expected.append("| Current State | Event | Guard | Next State | Side Effects |\n");
        expected.append("|---|---|---|---|---|\n");

        for (FsmTransitionTable.Rule rule : FsmTransitionTable.getRules()) {
            // Use effectiveEffects so expected == what generateFsmDocs writes
            String sideEffectsStr = FsmTransitionTable.effectiveEffects(rule).stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining(", "));

            expected.append(String.format("| %s | %s | %s | %s | %s |\n",
                rule.from().name(),
                rule.eventType().getSimpleName(),
                rule.guard().description(),
                rule.to().name(),
                sideEffectsStr
            ));
        }

        String actualContent = Files.exists(fsmDoc) ? Files.readString(fsmDoc) : "";
        assertEquals(expected.toString(), actualContent,
            "docs/FSM.md is out of date. Run: ./gradlew generateFsmDocs");
    }
}
