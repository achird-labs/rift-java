package io.github.achirdlabs.rift.junit5;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stateful in-process stand-in for the Rift admin API (JDK {@code com.sun.net.httpserver}, zero
 * deps). It assigns sequential ports to created imposters, tracks per-imposter recorded requests and
 * stub counts, and honours the reset calls the Spring integration issues (DELETE savedRequests, PUT
 * stubs, scenarios reset). Tests use {@link #pushRecorded} to simulate the app hitting an imposter.
 */
final class FakeRiftAdmin implements AutoCloseable {

    /** Per-imposter server-side state. */
    static final class ImposterState {
        final int port;
        final List<String> savedRequests = new CopyOnWriteArrayList<>();
        final AtomicInteger stubAdds = new AtomicInteger();
        final AtomicInteger proxyResponseClears = new AtomicInteger();

        ImposterState(int port) {
            this.port = port;
        }
    }

    private static final Pattern IMPOSTER_PORT = Pattern.compile("/imposters/(\\d+)");

    private final HttpServer server;
    private final AtomicInteger nextPort = new AtomicInteger(5000);
    private final Map<Integer, ImposterState> imposters = new ConcurrentHashMap<>();
    final AtomicInteger interceptStarts = new AtomicInteger();
    final AtomicInteger interceptRuleAdds = new AtomicInteger();
    final AtomicInteger interceptRuleClears = new AtomicInteger();
    /** The body of the last {@code POST /intercept}, or null if none was sent. */
    volatile String lastInterceptStart;

    FakeRiftAdmin() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        server.createContext("/", this::dispatch);
        server.start();
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Simulate a request the imposter recorded, so {@code recorded()}/{@code verify()} can see it. */
    void pushRecorded(int port, String method, String path) {
        ImposterState state = imposters.get(port);
        if (state != null) {
            state.savedRequests.add("{\"method\":\"" + method + "\",\"path\":\"" + path + "\"}");
        }
    }

    ImposterState state(int port) {
        return imposters.get(port);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.equals("/intercept") && method.equals("POST")) {
            lastInterceptStart = body;
        }
        String response = route(method, path);
        byte[] out = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        try (var os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private String route(String method, String path) {
        if (path.equals("/config")) {
            return "{\"version\":\"0.13.1\",\"commit\":\"test\"}";
        }
        if (path.equals("/intercept") && method.equals("POST")) {
            interceptStarts.incrementAndGet();
            return "{\"interceptPort\":19000,\"interceptUrl\":\"http://127.0.0.1:19000\"}";
        }
        if (path.equals("/intercept/rules")) {
            if (method.equals("POST")) {
                interceptRuleAdds.incrementAndGet();
                return "{}";
            }
            if (method.equals("DELETE")) {
                interceptRuleClears.incrementAndGet();
                return "{}";
            }
            return "[]"; // GET
        }
        if (path.equals("/intercept/ca.pem")) {
            return "-----BEGIN CERTIFICATE-----\ndummy\n-----END CERTIFICATE-----";
        }
        if (path.equals("/imposters") && method.equals("POST")) {
            int port = nextPort.getAndIncrement();
            imposters.put(port, new ImposterState(port));
            return imposterJson(port);
        }
        if (path.equals("/imposters") && method.equals("DELETE")) {
            imposters.clear();
            return "{\"imposters\":[]}";
        }
        Matcher m = IMPOSTER_PORT.matcher(path);
        if (m.find()) {
            int port = Integer.parseInt(m.group(1));
            ImposterState state = imposters.get(port);
            if (path.endsWith("/savedRequests")) {
                if (method.equals("DELETE")) {
                    if (state != null) {
                        state.savedRequests.clear();
                    }
                    return "{}";
                }
                return savedRequestsJson(state);
            }
            if (path.endsWith("/savedProxyResponses") && method.equals("DELETE")) {
                if (state != null) {
                    state.proxyResponseClears.incrementAndGet();
                }
                return "{}";
            }
            if (path.endsWith("/stubs") && method.equals("POST") && state != null) {
                state.stubAdds.incrementAndGet();
                return "{}";
            }
            if (path.matches("/imposters/\\d+") && method.equals("DELETE")) {
                imposters.remove(port);
                return "{}";
            }
            if (path.matches("/imposters/\\d+") && method.equals("GET")) {
                return imposterJson(port);
            }
        }
        return "{}"; // lenient default for PUT stubs, scenarios reset, enable/disable, etc.
    }

    private static String imposterJson(int port) {
        return "{\"port\":" + port + ",\"protocol\":\"http\",\"recordRequests\":true,\"stubs\":[]}";
    }

    private static String savedRequestsJson(ImposterState state) {
        if (state == null) {
            return "{\"requests\":[]}";
        }
        return "{\"requests\":[" + String.join(",", state.savedRequests) + "]}";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
