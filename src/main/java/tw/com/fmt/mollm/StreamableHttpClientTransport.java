package tw.com.fmt.mollm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.ClientMcpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

public class StreamableHttpClientTransport implements ClientMcpTransport {
    private final String baseUrl;
    private final Map<String, String> headers;
    private final String authToken;
    private final ObjectMapper mapper;
    private final HttpClient client;

    private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;

    public StreamableHttpClientTransport(String baseUrl, Map<String, String> headers, String authToken, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.headers = headers;
        this.authToken = authToken;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        this.handler = handler;
        return Mono.empty();
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.defer(() -> {
            try {
                String json = mapper.writeValueAsString(message);
                var requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));

                if (headers != null) {
                    for (var entry : headers.entrySet()) {
                        requestBuilder.header(entry.getKey(), entry.getValue());
                    }
                }
                if (authToken != null && !authToken.isBlank()) {
                    if (!authToken.startsWith("Bearer ") && !authToken.startsWith("Basic ") && (headers == null || !headers.containsKey("Authorization"))) {
                        requestBuilder.header("Authorization", "Bearer " + authToken);
                    } else {
                        requestBuilder.header("Authorization", authToken);
                    }
                }

                return Mono.fromFuture(client.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString()))
                        .flatMap(response -> {
                            if (response.statusCode() >= 400) {
                                return Mono.error(new RuntimeException("HTTP Error: " + response.statusCode() + " " + response.body()));
                            }
                            try {
                                McpSchema.JSONRPCMessage responseMsg = mapper.readValue(response.body(), McpSchema.JSONRPCMessage.class);
                                if (this.handler != null) {
                                    return this.handler.apply(Mono.just(responseMsg)).then();
                                }
                            } catch (Exception e) {
                                return Mono.error(e);
                            }
                            return Mono.empty();
                        });
            } catch (Exception e) {
                return Mono.error(e);
            }
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.empty();
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
        return mapper.convertValue(data, typeRef);
    }
}
