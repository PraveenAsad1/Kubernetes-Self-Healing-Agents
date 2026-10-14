package com.safeheal.agent;

import java.util.Optional;

public class TriageRouter {

    public Optional<String> route(DetectedFailure failure) {
        if (failure == null || failure.termination() == null) {
            return Optional.empty();
        }
        ContainerTermination term = failure.termination();
        if ("OOMKilled".equals(term.reason()) && term.exitCode() == 137) {
            return Optional.of("oom");
        }
        return Optional.empty();
    }
}
