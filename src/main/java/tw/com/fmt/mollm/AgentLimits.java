package tw.com.fmt.mollm;

record AgentLimits(int rounds, int operations, boolean unlimited) {
    static final int DEFAULT_ROUNDS = 80, DEFAULT_OPERATIONS = 256;
    static final int MAX_ROUNDS = 200, MAX_OPERATIONS = 2000;
    AgentLimits(int rounds, int operations) { this(rounds, operations, false); }
    boolean roundsReached(long used) { return !unlimited && used >= rounds; }
    boolean operationsReached(long used) { return !unlimited && used >= operations; }
    String roundProgress(long used) { return used + "/" + (unlimited ? "不設限" : rounds); }
    String operationProgress(long used) { return used + "/" + (unlimited ? "不設限" : operations); }
    AgentLimits {
        if (rounds < 1 || rounds > MAX_ROUNDS || operations < 1 || operations > MAX_OPERATIONS)
            throw new IllegalArgumentException("Agent 上限超出允許範圍。");
    }
    static AgentLimits defaults() { return new AgentLimits(DEFAULT_ROUNDS, DEFAULT_OPERATIONS); }
    static AgentLimits from(MollmSettings.Data state) {
        return new AgentLimits(state.agentMaxRounds >= 1 && state.agentMaxRounds <= MAX_ROUNDS ? state.agentMaxRounds : DEFAULT_ROUNDS,
                state.agentMaxOperations >= 1 && state.agentMaxOperations <= MAX_OPERATIONS ? state.agentMaxOperations : DEFAULT_OPERATIONS,
                state.agentUnlimited);
    }
}
