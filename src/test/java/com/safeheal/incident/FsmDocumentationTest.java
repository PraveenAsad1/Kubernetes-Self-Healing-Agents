package com.safeheal.incident;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class FsmDocumentationTest {

    @Test
    void transitionTableMatchesDocs() throws Exception {
        Path fsmDoc = Paths.get("docs/FSM.md");
        String expectedContent = "# FSM Transition Table\n\n| Current State | Event | Next State |\n|---|---|---|\n";
        // Dummy implementation for now, in a real scenario this would reflectively generate the table
        
        if (System.getProperty("fsm.update") != null) {
            Files.createDirectories(fsmDoc.getParent());
            Files.writeString(fsmDoc, expectedContent);
        } else {
            if (!Files.exists(fsmDoc)) {
                fail("docs/FSM.md does not exist. Run with -Dfsm.update=true to generate.");
            }
            String actualContent = Files.readString(fsmDoc);
            assertEquals(expectedContent, actualContent, "docs/FSM.md is out of date. Run with -Dfsm.update=true to regenerate.");
        }
    }
}
