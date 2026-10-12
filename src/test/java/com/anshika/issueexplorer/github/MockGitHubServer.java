package com.anshika.issueexplorer.github;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class MockGitHubServer implements AutoCloseable {
    public record Request(String method, String path, String authorization, String accept, String apiVersion) { }

    private record Response(int status, byte[] body, Map<String, String> headers, long length) { }
    private final Map<String, Response> routes = new ConcurrentHashMap<>();
    public final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile int status = 200;
    private volatile String body = "{\"title\":\"Sample issue\",\"body\":\"Issue details\"}";
    private volatile Map<String, String> headers = Map.of();
    private volatile long delayMillis;
    public final AtomicReference<Request> request = new AtomicReference<>();
    public final AtomicInteger requestCount = new AtomicInteger();

    public MockGitHubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                requestCount.incrementAndGet();
                var incoming = exchange.getRequestHeaders();
                request.set(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                        incoming.getFirst("Authorization"), incoming.getFirst("Accept"),
                        incoming.getFirst("X-GitHub-Api-Version")));
                requests.add(request.get());
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                var response = routes.getOrDefault(exchange.getRequestURI().toString(), new Response(status, body.getBytes(StandardCharsets.UTF_8), headers, body.getBytes(StandardCharsets.UTF_8).length));
                response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                }
                byte[] bytes = response.body();
                exchange.sendResponseHeaders(response.status(), response.length());
                exchange.getResponseBody().write(bytes);
            } catch (IOException exception) {
                // Clients may close the connection when testing timeout handling.
            }
        });
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    public void respond(int status, String body, Map<String, String> headers) {
        this.status = status;
        this.body = body;
        this.headers = headers;
    }

    public void respondAt(String path, int status, String body, Map<String, String> headers) {
        routes.put(path, new Response(status, body.getBytes(StandardCharsets.UTF_8), headers, body.getBytes(StandardCharsets.UTF_8).length));
    }

    public void respondBytes(String path, byte[] bytes, boolean chunked) {
        routes.put(path, new Response(200, bytes, Map.of("Content-Type", "application/zip"), chunked ? 0 : bytes.length));
    }

    public void respondTruncated(String path, byte[] bytes) {
        routes.put(path, new Response(200, bytes, Map.of(), bytes.length + 100));
    }

    public void delay(long millis) {
        delayMillis = millis;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}


