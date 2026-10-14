package tw.com.fmt.mollm;

/** Pauses a segment after two consecutive failures of the same operation/cause. */
final class AgentFailureGuard {
    private String previous = "";
    private int count;
    boolean record(String tool, String target, String result) {
        if (!result.startsWith("Error:") && !result.startsWith("Timed out") && !result.matches("(?s)Exit code: [1-9]\\d*\\..*")) {
            previous = ""; count = 0; return false;
        }
        String cause = result.lines().findFirst().orElse(result);
        // Argument length changes are still the same incomplete-JSON failure.
        if (cause.contains("INVALID_TOOL_ARGUMENTS")) cause = "INVALID_TOOL_ARGUMENTS";
        if (cause.contains("OUTPUT_TRUNCATED")) cause = "OUTPUT_TRUNCATED";
        String identity = tool + "\0" + target + "\0" + cause;
        count = identity.equals(previous) ? count + 1 : 1;
        previous = identity;
        return count >= 2;
    }
}
