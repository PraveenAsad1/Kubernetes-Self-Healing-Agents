package com.safeheal.incident;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

public class FsmDocGenerator {
    public static void main(String[] args) throws Exception {
        Path fsmDoc = Paths.get("docs/FSM.md");
        StringBuilder expected = new StringBuilder();
        expected.append("# FSM Transition Table\n\n");
        expected.append("| Current State | Event | Guard | Next State | Side Effects |\n");
        expected.append("|---|---|---|---|---|\n");
        
        for (FsmTransitionTable.Rule rule : FsmTransitionTable.getRules()) {
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
        Files.createDirectories(fsmDoc.getParent());
        Files.writeString(fsmDoc, expected.toString());
        System.out.println("Generated FSM.md");
    }
}
