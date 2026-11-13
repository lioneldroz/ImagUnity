package com.imagunity.transport.iu;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Répartiteur des essais : chaque requête va à l'instance suivante, une instance injoignable est sautée (comme un
 * répartiteur qui retire une instance en panne). Compte les requêtes servies par chaque instance.
 */
final class RoundRobinProxy implements AutoCloseable {

    private static final Set<String> HOP = Set.of("connection", "content-length", "host", "transfer-encoding",
            "upgrade", "expect", "keep-alive");

    final List<URI> backends = new CopyOnWriteArrayList<>();
    final Map<URI, AtomicInteger> served = new ConcurrentHashMap<>();
    private final AtomicInteger next = new AtomicInteger();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final HttpServer server;

    RoundRobinProxy(List<URI> backends) throws IOException {
        this.backends.addAll(backends);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::forward);
        server.start();
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void forward(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        IOException last = null;
        for (int attempt = 0; attempt < backends.size(); attempt++) {
            URI backend = backends.get(Math.floorMod(next.getAndIncrement(), backends.size()));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(backend + exchange.getRequestURI()
                    .toString())).timeout(Duration.ofMinutes(2))
                    .method(exchange.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!HOP.contains(name.toLowerCase())) {
                    values.forEach(v -> request.header(name, v));
                }
            });
            HttpResponse<byte[]> response;
            try {
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                last = e; // instance injoignable : la suivante
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            served.computeIfAbsent(backend, b -> new AtomicInteger()).incrementAndGet();
            response.headers().map().forEach((name, values) -> {
                if (!HOP.contains(name.toLowerCase()) && !name.startsWith(":")) {
                    exchange.getResponseHeaders().put(name, values);
                }
            });
            byte[] answer = response.body();
            exchange.sendResponseHeaders(response.statusCode(), answer.length == 0 ? -1 : answer.length);
            if (answer.length > 0) {
                exchange.getResponseBody().write(answer);
            }
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(502, -1);
        exchange.close();
        if (last != null && backends.isEmpty()) {
            throw last;
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
