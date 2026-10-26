package com.infinitestack.reports.blocks.sathub.imagery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** The range fetcher against a local JDK HTTP server: 206, ignored Range, retry, redirect. */
class HttpRangeFetcherTest {

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d+)");

    private final byte[] file = new byte[100_000];
    private final AtomicInteger flakyCalls = new AtomicInteger();
    private final AtomicInteger downCalls = new AtomicInteger();
    private HttpServer server;
    private String base;
    private final HttpRangeFetcher fetcher = new HttpRangeFetcher(Duration.ofSeconds(5));

    @BeforeEach
    void start() throws IOException {
        for (int i = 0; i < file.length; i++) file[i] = (byte) (i * 31);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/range", this::partial);
        server.createContext("/ignore", exchange -> send(exchange, 200, file));
        server.createContext("/small", exchange -> send(exchange, 200, Arrays.copyOf(file, 50)));
        server.createContext("/flaky", exchange -> {
            if (flakyCalls.incrementAndGet() == 1) send(exchange, 503, new byte[0]);
            else partial(exchange);
        });
        server.createContext("/down", exchange -> {
            downCalls.incrementAndGet();
            send(exchange, 500, new byte[0]);
        });
        server.createContext("/missing", exchange -> send(exchange, 404, new byte[0]));
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/range");
            send(exchange, 302, new byte[0]);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void partial(HttpExchange exchange) throws IOException {
        Matcher m = RANGE.matcher(exchange.getRequestHeaders().getFirst("Range"));
        if (!m.matches()) {
            send(exchange, 400, new byte[0]);
            return;
        }
        int from = Integer.parseInt(m.group(1));
        if (from >= file.length) {
            send(exchange, 416, new byte[0]);
            return;
        }
        int to = Math.min(file.length - 1, Integer.parseInt(m.group(2)));
        exchange.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + file.length);
        send(exchange, 206, Arrays.copyOfRange(file, from, to + 1));
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void readsTheRangeAskedFor() throws IOException {
        assertThat(fetcher.fetch(base + "/range", 1000, 300)).isEqualTo(Arrays.copyOfRange(file, 1000, 1300));
        assertThat(fetcher.fetch(base + "/range", 99_900, 300)).hasSize(100);
        assertThat(fetcher.fetch(base + "/range", 200_000, 10)).isEmpty();
        assertThat(fetcher.fetch(base + "/moved", 5, 5)).isEqualTo(Arrays.copyOfRange(file, 5, 10));
    }

    @Test
    void rejectsAServerIgnoringRangeUnlessTheFileFits() throws IOException {
        assertThatThrownBy(() -> fetcher.fetch(base + "/ignore", 0, 1000))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ignored Range");
        assertThat(fetcher.fetch(base + "/small", 10, 1000)).isEqualTo(Arrays.copyOfRange(file, 10, 50));
    }

    @Test
    void retriesOnceOnServerError() throws IOException {
        assertThat(fetcher.fetch(base + "/flaky", 0, 10)).isEqualTo(Arrays.copyOf(file, 10));
        assertThat(flakyCalls.get()).isEqualTo(2);

        assertThatThrownBy(() -> fetcher.fetch(base + "/down", 0, 10)).isInstanceOf(IOException.class);
        assertThat(downCalls.get()).isEqualTo(2);
        assertThatThrownBy(() -> fetcher.fetch(base + "/missing", 0, 10)).hasMessageContaining("404");
    }
}
