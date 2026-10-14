package com.safeheal.incident;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class FsmTransitionTable {

    public record Rule(IncidentState from, Class<? extends IncidentEvent> eventType, Guard guard, IncidentState to, Set<Transition.SideEffect> sideEffects) {}

    public interface Guard {
        boolean test(Incident incident, IncidentEvent event, FsmConfig config, Clock clock);
        String description();
    }

    private static final List<Rule> RULES_MUTABLE = new ArrayList<>();

    static {
        // DETECTED
        addRule(IncidentState.DETECTED, IncidentEvent.ProcessingStarted.class, noGuard(), IncidentState.INVESTIGATING, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.DETECTED, IncidentEvent.WorkloadDeleted.class, noGuard(), IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.DETECTED, IncidentEvent.Recurrence.class, noGuard(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.DETECTED, IncidentEvent.Timeout.class, noGuard(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG));

        // INVESTIGATING
        addRule(IncidentState.INVESTIGATING, IncidentEvent.EvidenceGathered.class, noGuard(), IncidentState.DIAGNOSING, EnumSet.of(Transition.SideEffect.STORE_EVIDENCE, Transition.SideEffect.RESET_TRANSIENT_RETRIES));
        addRule(IncidentState.INVESTIGATING, IncidentEvent.K8sReadFailed.class, transientBelowLimit(), IncidentState.INVESTIGATING, EnumSet.of(Transition.SideEffect.INCREMENT_TRANSIENT_RETRY));
        addRule(IncidentState.INVESTIGATING, IncidentEvent.K8sReadFailed.class, transientAtLimit(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.INVESTIGATING, IncidentEvent.Timeout.class, noGuard(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.INVESTIGATING, IncidentEvent.WorkloadDeleted.class, noGuard(), IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG));

        // DIAGNOSING
        addRule(IncidentState.DIAGNOSING, IncidentEvent.ProposalAccepted.class, noGuard(), IncidentState.AWAITING_APPROVAL, EnumSet.of(Transition.SideEffect.STORE_PROPOSAL, Transition.SideEffect.RESET_TRANSIENT_RETRIES));
        addRule(IncidentState.DIAGNOSING, IncidentEvent.LlmFailed.class, transientBelowLimit(), IncidentState.DIAGNOSING, EnumSet.of(Transition.SideEffect.INCREMENT_TRANSIENT_RETRY));
        addRule(IncidentState.DIAGNOSING, IncidentEvent.LlmFailed.class, transientAtLimit(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.DIAGNOSING, IncidentEvent.LlmInvalid.class, transientBelowLimit(), IncidentState.DIAGNOSING, EnumSet.of(Transition.SideEffect.INCREMENT_TRANSIENT_RETRY));
        addRule(IncidentState.DIAGNOSING, IncidentEvent.LlmInvalid.class, transientAtLimit(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.DIAGNOSING, IncidentEvent.PolicyDenied.class, noGuard(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.DIAGNOSING, IncidentEvent.Timeout.class, noGuard(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.DIAGNOSING, IncidentEvent.WorkloadDeleted.class, noGuard(), IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG));

        // AWAITING_APPROVAL
        addRule(IncidentState.AWAITING_APPROVAL, IncidentEvent.Approved.class, approvalValid(), IncidentState.REMEDIATING, EnumSet.of(Transition.SideEffect.CREATE_INTENT, Transition.SideEffect.RESET_TRANSIENT_RETRIES));
        addRule(IncidentState.AWAITING_APPROVAL, IncidentEvent.Rejected.class, noGuard(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.AWAITING_APPROVAL, IncidentEvent.Timeout.class, noGuard(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.AWAITING_APPROVAL, IncidentEvent.WorkloadDeleted.class, noGuard(), IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG));

        // REMEDIATING
        addRule(IncidentState.REMEDIATING, IncidentEvent.ExecutionPolicyDenied.class, noGuard(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.TERMINATE_INTENT));
        addRule(IncidentState.REMEDIATING, IncidentEvent.Conflict.class, attemptBelowLimit(), IncidentState.INVESTIGATING, EnumSet.of(Transition.SideEffect.INCREMENT_ATTEMPT, Transition.SideEffect.TERMINATE_INTENT, Transition.SideEffect.RESET_TRANSIENT_RETRIES, Transition.SideEffect.VOID_APPROVAL));
        addRule(IncidentState.REMEDIATING, IncidentEvent.Conflict.class, attemptAtLimit(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.TERMINATE_INTENT, Transition.SideEffect.VOID_APPROVAL));
        addRule(IncidentState.REMEDIATING, IncidentEvent.PatchFailed.class, transientBelowLimit(), IncidentState.REMEDIATING, EnumSet.of(Transition.SideEffect.INCREMENT_TRANSIENT_RETRY));
        addRule(IncidentState.REMEDIATING, IncidentEvent.PatchFailed.class, transientAtLimit(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.TERMINATE_INTENT));
        addRule(IncidentState.REMEDIATING, IncidentEvent.Applied.class, noGuard(), IncidentState.VERIFYING, EnumSet.of(Transition.SideEffect.MARK_INTENT_APPLIED, Transition.SideEffect.RESET_TRANSIENT_RETRIES));
        addRule(IncidentState.REMEDIATING, IncidentEvent.ReconcileResume.class, noGuard(), IncidentState.VERIFYING, EnumSet.of(Transition.SideEffect.SYNC_INTENT_STATE));
        addRule(IncidentState.REMEDIATING, IncidentEvent.Timeout.class, noGuard(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.TERMINATE_INTENT));
        addRule(IncidentState.REMEDIATING, IncidentEvent.WorkloadDeleted.class, noGuard(), IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.TERMINATE_INTENT));

        // VERIFYING
        addRule(IncidentState.VERIFYING, IncidentEvent.Verified.class, noGuard(), IncidentState.RESOLVED, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.VERIFYING, IncidentEvent.VerificationFailed.class, noGuard(), IncidentState.REFLECTING, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.VERIFYING, IncidentEvent.Timeout.class, noGuard(), IncidentState.REFLECTING, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.VERIFYING, IncidentEvent.WorkloadDeleted.class, noGuard(), IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.VERIFYING, IncidentEvent.Recurrence.class, noGuard(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG));

        // REFLECTING
        addRule(IncidentState.REFLECTING, IncidentEvent.ReflectionCompleted.class, attemptBelowLimit(), IncidentState.INVESTIGATING, EnumSet.of(Transition.SideEffect.INCREMENT_ATTEMPT, Transition.SideEffect.RESET_TRANSIENT_RETRIES));
        addRule(IncidentState.REFLECTING, IncidentEvent.ReflectionCompleted.class, attemptAtLimit(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.REFLECTING, IncidentEvent.WorkloadDeleted.class, noGuard(), IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG));
        addRule(IncidentState.REFLECTING, IncidentEvent.Timeout.class, noGuard(), IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG));
    }
    
    private static final List<Rule> RULES = Collections.unmodifiableList(RULES_MUTABLE);

    private static void addRule(IncidentState from, Class<? extends IncidentEvent> eventType, Guard guard, IncidentState to, EnumSet<Transition.SideEffect> sideEffects) {
        RULES_MUTABLE.add(new Rule(from, eventType, guard, to, Collections.unmodifiableSet(EnumSet.copyOf(sideEffects))));
    }

    private static Guard noGuard() {
        return new Guard() {
            @Override public boolean test(Incident incident, IncidentEvent event, FsmConfig config, Clock clock) { return true; }
            @Override public String description() { return "None"; }
        };
    }

    private static Guard transientBelowLimit() {
        return new Guard() {
            @Override public boolean test(Incident incident, IncidentEvent event, FsmConfig config, Clock clock) { return incident.transientRetries() < config.maxTransientRetries(); }
            @Override public String description() { return "transientRetries < maxTransientRetries"; }
        };
    }

    private static Guard transientAtLimit() {
        return new Guard() {
            @Override public boolean test(Incident incident, IncidentEvent event, FsmConfig config, Clock clock) { return incident.transientRetries() >= config.maxTransientRetries(); }
            @Override public String description() { return "transientRetries >= maxTransientRetries"; }
        };
    }

    private static Guard attemptBelowLimit() {
        return new Guard() {
            @Override public boolean test(Incident incident, IncidentEvent event, FsmConfig config, Clock clock) { return incident.attempt() < config.maxAttempts(); }
            @Override public String description() { return "attempt < maxAttempts"; }
        };
    }

    private static Guard attemptAtLimit() {
        return new Guard() {
            @Override public boolean test(Incident incident, IncidentEvent event, FsmConfig config, Clock clock) { return incident.attempt() >= config.maxAttempts(); }
            @Override public String description() { return "attempt >= maxAttempts"; }
        };
    }

    private static Guard approvalValid() {
        return new Guard() {
            @Override public boolean test(Incident incident, IncidentEvent event, FsmConfig config, Clock clock) {
                if (event instanceof IncidentEvent.Approved approved) {
                    return !approved.approvedAt().plus(config.approvalTtl()).isBefore(clock.instant());
                }
                return false;
            }
            @Override public String description() { return "approval is not expired"; }
        };
    }

    public static List<Rule> getRules() { return RULES; }

    /**
     * Computes the effective side-effect set for a rule: the rule's declared effects plus
     * {@link Transition.SideEffect#CLOSE_INCIDENT} when the target state is terminal.
     * Both decide() and FsmDocGenerator must use this method so that docs/FSM.md and runtime
     * behaviour are always consistent.
     */
    public static Set<Transition.SideEffect> effectiveEffects(Rule rule) {
        if (rule.to().isTerminal()) {
            EnumSet<Transition.SideEffect> e = EnumSet.copyOf(rule.sideEffects());
            e.add(Transition.SideEffect.CLOSE_INCIDENT);
            return Collections.unmodifiableSet(e);
        }
        return rule.sideEffects();
    }

    public static Transition decide(Incident incident, IncidentEvent event, FsmConfig config, Clock clock) {
        for (Rule rule : RULES) {
            if (rule.from() == incident.state() && rule.eventType().isInstance(event)) {
                if (rule.guard().test(incident, event, config, clock)) {
                    return new Transition(rule.to(), effectiveEffects(rule));
                }
            }
        }
        throw new IllegalTransitionException(incident.state(), event, "No valid transition rule matched");
    }
}
