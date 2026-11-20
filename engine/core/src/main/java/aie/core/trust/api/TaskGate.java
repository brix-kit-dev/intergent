package aie.core.trust.api;

/**
 * The gate of blueprint 9.1: which tasks this deployment offers. It stands after identity and before the data class, so
 * a request for a task the gate does not open is refused before anything of it is looked at further. Where the engine
 * runs decides the answer, through the policy its deployment loads, never through code (C-8, A-5).
 */
@FunctionalInterface
public interface TaskGate {

    /** Whether a request for this task may go on here. */
    boolean opens(String task);
}
