package tw.com.fmt.mollm;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service(Service.Level.APP)
@State(name = "MoLLM", storages = @Storage("mollm.xml"))
public final class MollmSettings implements PersistentStateComponent<MollmSettings.Data> {
    public static final class Data {
        public List<Server> servers = new ArrayList<>();
        public int timeoutMs = 120_000;
        public String reasoningDisplay = "quoted";
        public int chatFontSize;
        public String chatFontFamily = "__chat_default__";
        public int usageRefreshSeconds = 60;
        public int interfaceScalePercent = 100;
        public int agentMaxRounds = AgentLimits.DEFAULT_ROUNDS;
        public int agentMaxOperations = AgentLimits.DEFAULT_OPERATIONS;
        public boolean agentUnlimited = false;
        public List<DirectoryGrant> directoryGrants = new ArrayList<>();
    }

    public static final class DirectoryGrant {
        public String directory = "";
        public boolean allowFiles;
        public boolean allowCommands;
    }

    // Mutable beans are required by IntelliJ's XML state serializer. Never store keys here.
    public static final class Server {
        public String id = UUID.randomUUID().toString();
        public String label = "MoLLM";
        public String baseUrl = "https://mollm.fmt.com.tw/v1";
        public List<String> models = new ArrayList<>();
        public Map<String, ModelOverride> overrides = new LinkedHashMap<>();
        public int maxOutputTokens = 8192;
        @Override public String toString() { return label; }
    }

    public static final class ModelOverride {
        public boolean forceToolCalling;
        public boolean forceImageInput;
        public int maxOutputTokens;
        public String reasoningEffort = "";
        public int thinkingBudgetTokens;
    }

    private volatile Data data = new Data();
    public static MollmSettings getInstance() {
        return ApplicationManager.getApplication().getService(MollmSettings.class);
    }
    @Override public @NotNull Data getState() { return data; }
    @Override public void loadState(@NotNull Data state) { data = state; }
    List<Server> servers() { return List.copyOf(data.servers); }
    int usageRefreshSeconds() {
        return switch (data.usageRefreshSeconds) {
            case 30, 60, 180 -> data.usageRefreshSeconds;
            default -> 60;
        };
    }
    void save(Server server) {
        var updated = new ArrayList<>(data.servers);
        updated.removeIf(existing -> existing.id.equals(server.id));
        updated.add(server);
        data.servers = updated;
    }
    void remove(String id) {
        data.servers = data.servers.stream().filter(server -> !server.id.equals(id)).toList();
    }
}
