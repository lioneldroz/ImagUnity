package com.imagunity.relay;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service S3 minimal pour les essais (adressage par chemin) : compartiments, dépôt, lecture et suppression d'objets.
 * Les signatures ne sont pas vérifiées. Données synthétiques uniquement.
 */
final class FakeS3 implements AutoCloseable {

    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final Set<String> buckets = ConcurrentHashMap.newKeySet();
    /** Statut imposé à toutes les réponses (panne simulée), ou 0. */
    volatile int failWith;
    private final HttpServer server;

    FakeS3() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath().substring(1);
            int slash = path.indexOf('/');
            String bucket = slash < 0 ? path : path.substring(0, slash);
            String key = slash < 0 ? null : path.substring(slash + 1);
            int status;
            byte[] answer = new byte[0];
            if (failWith != 0) {
                status = failWith;
            } else if (key == null) {
                switch (exchange.getRequestMethod()) {
                    case "HEAD" -> status = buckets.contains(bucket) ? 200 : 404;
                    case "PUT" -> {
                        buckets.add(bucket);
                        status = 200;
                    }
                    default -> status = 405;
                }
            } else if (!buckets.contains(bucket)) {
                status = 404;
            } else {
                switch (exchange.getRequestMethod()) {
                    case "PUT" -> {
                        objects.put(bucket + "/" + key, body);
                        status = 200;
                    }
                    case "GET" -> {
                        answer = objects.get(bucket + "/" + key);
                        status = answer == null ? 404 : 200;
                        if (answer == null) {
                            answer = new byte[0];
                        }
                    }
                    case "DELETE" -> {
                        objects.remove(bucket + "/" + key);
                        status = 204;
                    }
                    default -> status = 405;
                }
            }
            boolean empty = answer.length == 0 || exchange.getRequestMethod().equals("HEAD");
            exchange.sendResponseHeaders(status, empty ? -1 : answer.length);
            if (!empty) {
                exchange.getResponseBody().write(answer);
            }
            exchange.close();
        });
        server.start();
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
