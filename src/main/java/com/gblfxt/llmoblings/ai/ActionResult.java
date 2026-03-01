package com.gblfxt.llmoblings.ai;

/**
 * Result of executing a CompanionAction.
 * Controls whether the iterative action loop continues or stops.
 */
public record ActionResult(String actionName, boolean success, String resultText, boolean isTerminal) {

    /**
     * The action completed and the loop should stop (e.g., follow, mine, attack).
     */
    public static ActionResult terminal(String actionName, String result) {
        return new ActionResult(actionName, true, result, true);
    }

    /**
     * The action was a query — its result should be fed back to the LLM for the next iteration.
     */
    public static ActionResult query(String actionName, String result) {
        return new ActionResult(actionName, true, result, false);
    }

    /**
     * The action failed — feed the failure reason back so the LLM can adapt.
     */
    public static ActionResult failure(String actionName, String reason) {
        return new ActionResult(actionName, false, reason, false);
    }
}
