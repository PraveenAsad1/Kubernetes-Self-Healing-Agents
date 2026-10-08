package com.safeheal.incident;

import java.time.Clock;

public class FsmTransitionTable {

    public static Transition decide(Incident incident, IncidentEvent event, FsmConfig config, Clock clock) {
        if (incident.state().isTerminal()) {
            throw new IllegalTransitionException(incident.state(), event, "Incident is in a terminal state");
        }

        return switch (incident.state()) {
            case DETECTED -> handleDetected(incident, event, config);
            case INVESTIGATING -> handleInvestigating(incident, event, config);
            case DIAGNOSING -> handleDiagnosing(incident, event, config);
            case AWAITING_APPROVAL -> handleAwaitingApproval(incident, event, config, clock);
            case REMEDIATING -> handleRemediating(incident, event, config);
            case VERIFYING -> handleVerifying(incident, event, config);
            case REFLECTING -> handleReflecting(incident, event, config);
            default -> throw new IllegalTransitionException(incident.state(), event, "Unhandled state");
        };
    }

    private static Transition handleDetected(Incident incident, IncidentEvent event, FsmConfig config) {
        if (event instanceof IncidentEvent.ProcessingStarted) {
            return new Transition(IncidentState.INVESTIGATING, Transition.SideEffect.NONE);
        } else if (event instanceof IncidentEvent.WorkloadDeleted) {
            return new Transition(IncidentState.CANCELLED, Transition.SideEffect.LOG);
        }
        throw illegal(incident, event);
    }

    private static Transition handleInvestigating(Incident incident, IncidentEvent event, FsmConfig config) {
        if (event instanceof IncidentEvent.EvidenceGathered) {
            return new Transition(IncidentState.DIAGNOSING, Transition.SideEffect.STORE_EVIDENCE);
        } else if (event instanceof IncidentEvent.K8sReadFailed) {
            if (incident.transientRetries() < config.maxTransientRetries()) {
                return new Transition(IncidentState.INVESTIGATING, Transition.SideEffect.INCREMENT_TRANSIENT_RETRY);
            }
            return new Transition(IncidentState.ESCALATED, Transition.SideEffect.LOG);
        } else if (event instanceof IncidentEvent.Timeout) {
            return new Transition(IncidentState.ESCALATED, Transition.SideEffect.LOG);
        } else if (event instanceof IncidentEvent.WorkloadDeleted) {
            return new Transition(IncidentState.CANCELLED, Transition.SideEffect.LOG);
        }
        throw illegal(incident, event);
    }

    private static Transition handleDiagnosing(Incident incident, IncidentEvent event, FsmConfig config) {
        if (event instanceof IncidentEvent.ProposalAccepted) {
            return new Transition(IncidentState.AWAITING_APPROVAL, Transition.SideEffect.STORE_PROPOSAL);
        } else if (event instanceof IncidentEvent.LlmFailed) {
            if (incident.transientRetries() < config.maxTransientRetries()) {
                return new Transition(IncidentState.DIAGNOSING, Transition.SideEffect.INCREMENT_TRANSIENT_RETRY);
            }
            return new Transition(IncidentState.ESCALATED, Transition.SideEffect.LOG);
        } else if (event instanceof IncidentEvent.LlmInvalid || event instanceof IncidentEvent.PolicyDenied) {
            return new Transition(IncidentState.ESCALATED, Transition.SideEffect.LOG);
        } else if (event instanceof IncidentEvent.Timeout) {
            return new Transition(IncidentState.ESCALATED, Transition.SideEffect.LOG);
        } else if (event instanceof IncidentEvent.WorkloadDeleted) {
            return new Transition(IncidentState.CANCELLED, Transition.SideEffect.LOG);
        }
        throw illegal(incident, event);
    }

    private static Transition handleAwaitingApproval(Incident incident, IncidentEvent event, FsmConfig config, Clock clock) {
        if (event instanceof IncidentEvent.Approved approved) {
            if (approved.approvedUntil().isBefore(clock.instant())) {
                throw new IllegalTransitionException(incident.state(), event, "Approval has expired");
            }
            return new Transition(IncidentState.REMEDIATING, Transition.SideEffect.CREATE_INTENT);
        } else if (event instanceof IncidentEvent.Rejected || event instanceof IncidentEvent.Timeout) {
            return new Transition(IncidentState.ESCALATED, Transition.SideEffect.LOG);
        } else if (event instanceof IncidentEvent.WorkloadDeleted) {
            return new Transition(IncidentState.CANCELLED, Transition.SideEffect.LOG);
        }
        throw illegal(incident, event);
    }

    private static Transition handleRemediating(Incident incident, IncidentEvent event, FsmConfig config) {
        if (event instanceof IncidentEvent.ExecutionPolicyDenied) {
            return new Transition(IncidentState.ESCALATED, Transition.SideEffect.TERMINATE_INTENT);
        } else if (event instanceof IncidentEvent.Conflict) {
            if (incident.attempt() < config.maxAttempts()) {
                return new Transition(IncidentState.INVESTIGATING, Transition.SideEffect.INCREMENT_ATTEMPT);
            }
            return new Transition(IncidentState.ESCALATED, Transition.SideEffect.TERMINATE_INTENT);
        } else if (event instanceof IncidentEvent.PatchFailed) {
            if (incident.transientRetries() < config.maxTransientRetries()) {
                return new Transition(IncidentState.REMEDIATING, Transition.SideEffect.INCREMENT_TRANSIENT_RETRY);
            }
            return new Transition(IncidentState.ESCALATED, Transition.SideEffect.TERMINATE_INTENT);
        } else if (event instanceof IncidentEvent.Applied) {
            return new Transition(IncidentState.VERIFYING, Transition.SideEffect.MARK_INTENT_APPLIED);
        } else if (event instanceof IncidentEvent.ReconcileResume) {
            return new Transition(IncidentState.VERIFYING, Transition.SideEffect.SYNC_INTENT_STATE);
        } else if (event instanceof IncidentEvent.Timeout) {
            return new Transition(IncidentState.ESCALATED, Transition.SideEffect.TERMINATE_INTENT);
        } else if (event instanceof IncidentEvent.WorkloadDeleted) {
            return new Transition(IncidentState.CANCELLED, Transition.SideEffect.TERMINATE_INTENT);
        }
        throw illegal(incident, event);
    }

    private static Transition handleVerifying(Incident incident, IncidentEvent event, FsmConfig config) {
        if (event instanceof IncidentEvent.Verified) {
            return new Transition(IncidentState.RESOLVED, Transition.SideEffect.LOG);
        } else if (event instanceof IncidentEvent.VerificationFailed || event instanceof IncidentEvent.Timeout) {
            return new Transition(IncidentState.REFLECTING, Transition.SideEffect.LOG);
        } else if (event instanceof IncidentEvent.WorkloadDeleted) {
            return new Transition(IncidentState.CANCELLED, Transition.SideEffect.LOG);
        }
        throw illegal(incident, event);
    }

    private static Transition handleReflecting(Incident incident, IncidentEvent event, FsmConfig config) {
        if (event instanceof IncidentEvent.ReflectionCompleted) {
            if (incident.attempt() < config.maxAttempts()) {
                return new Transition(IncidentState.INVESTIGATING, Transition.SideEffect.INCREMENT_ATTEMPT);
            }
            return new Transition(IncidentState.ESCALATED, Transition.SideEffect.LOG);
        }
        throw illegal(incident, event);
    }

    private static IllegalTransitionException illegal(Incident incident, IncidentEvent event) {
        return new IllegalTransitionException(incident.state(), event, "No valid transition rule matched");
    }
}
