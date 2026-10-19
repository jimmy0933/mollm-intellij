package tw.com.fmt.mollm;

import com.intellij.openapi.project.Project;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

final class McpConnectionManager {
    private static final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    private static final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

    static void initialize(Project project) {
        // Called when project opens or settings change.
        // We do lazy initialization during tool usage to avoid hanging startup.
    }

    static synchronized McpSyncClient getClient(MollmSettings.McpServerConfig config) {
        if (!config.enabled) return null;
        if (clients.containsKey(config.id)) {
            return clients.get(config.id);
        }

        McpSyncClient client = null;
        try {
            if ("stdio".equals(config.transport)) {
                var builder = ServerParameters.builder(config.executable)
                        .args(config.args.toArray(new String[0]));
                if (!config.workingDirectory.isBlank()) {
                    builder.workingDirectory(config.workingDirectory);
                }
                if (!config.env.isEmpty()) {
                    builder.env(config.env);
                }
                var transport = new StdioClientTransport(builder.build(), jsonMapper);
                client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(config.requestTimeoutSeconds)).build();
            } else if ("sse".equals(config.transport)) {
                var builder = HttpClientSseClientTransport.builder(config.baseUrl);

                builder.httpRequestCustomizer(requestBuilder -> {
                    // Set custom headers
                    for (var entry : config.headers.entrySet()) {
                        requestBuilder.header(entry.getKey(), entry.getValue());
                    }

                    // Inject auth token from PasswordSafe if requested
                    if (config.passAuthToken) {
                        String token = ApiKeys.get(config.id);
                        if (token != null && !token.isBlank()) {
                            // Automatically prefix with Bearer if there's no custom Authorization scheme already
                            if (!token.startsWith("Bearer ") && !token.startsWith("Basic ") && !config.headers.containsKey("Authorization")) {
                                requestBuilder.header("Authorization", "Bearer " + token);
                            } else {
                                requestBuilder.header("Authorization", token);
                            }
                        }
                    }
                });

                var transport = builder.build();
                client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(config.requestTimeoutSeconds)).build();
            }

            if (client != null) {
                client.initialize();
                clients.put(config.id, client);
            }
        } catch (Exception e) {
            // Failed to start
            if (client != null) client.closeGracefully();
            throw new RuntimeException("Failed to initialize MCP Server: " + config.name, e);
        }

        return client;
    }

    static synchronized void close(String serverId) {
        var client = clients.remove(serverId);
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {}
        }
    }

    static synchronized void closeAll() {
        for (var client : clients.values()) {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {}
        }
        clients.clear();
    }
}
