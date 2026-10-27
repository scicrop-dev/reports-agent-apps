package com.infinitestack.reports.blocks.sathub.imagery;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * {@link RangeFetcher} over HTTP(S) with {@code Range: bytes=a-b} (java.net.http), with connect and
 * request timeouts and one retry on a transient error (5xx, timeout, connection reset). A 200 answer
 * to a range request (server ignoring Range) is an error, and its body is not read: it would download
 * the whole band. The only 200 accepted is one no longer than the range asked for (a small file sent
 * whole). Thread-safe: one {@link HttpClient} shared by the bands read in parallel.
 */
public class HttpRangeFetcher implements RangeFetcher {

    private static final int ATTEMPTS = 2;

    private final Duration timeout;
    private final HttpClient client;

    /** @param timeout used both to connect and for the whole request */
    public HttpRangeFetcher(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public byte[] fetch(String url, long offset, int length) throws IOException {
        if (length <= 0) return new byte[0];
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Range", "bytes=" + offset + "-" + (offset + length - 1))
                .GET()
                .build();
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            HttpResponse<byte[]> response;
            try {
                response = client.send(request, info -> acceptable(info, length)
                        ? HttpResponse.BodySubscribers.ofByteArray()
                        : unread());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted reading " + url);
            } catch (IOException e) {
                last = e;
                continue;
            }
            int status = response.statusCode();
            if (status == 206) return response.body();
            if (status == 200 && response.body() != null) return slice(response.body(), offset, length);
            if (status == 416) return new byte[0]; // range starts past the end of the file
            last = new IOException("HTTP " + status + " reading bytes " + offset + "+" + length + " of " + url
                    + (status == 200 ? " (server ignored Range)" : ""));
            if (status < 500) break;
        }
        throw last;
    }

    /** The range asked for out of a whole file. */
    private static byte[] slice(byte[] whole, long offset, int length) {
        int from = (int) Math.min(offset, whole.length);
        return Arrays.copyOfRange(whole, from, Math.min(whole.length, from + length));
    }

    /** Whether to read the body: a partial answer, or a whole file that fits in the range asked for. */
    private static boolean acceptable(HttpResponse.ResponseInfo info, int length) {
        if (info.statusCode() == 206) return true;
        if (info.statusCode() != 200) return false;
        long contentLength = info.headers().firstValueAsLong("Content-Length").orElse(-1);
        return contentLength >= 0 && contentLength <= length;
    }

    /** A body that is never read: cancelling the subscription drops the connection (null body). */
    private static HttpResponse.BodySubscriber<byte[]> unread() {
        return new HttpResponse.BodySubscriber<>() {
            @Override
            public CompletionStage<byte[]> getBody() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.cancel();
            }

            @Override
            public void onNext(List<ByteBuffer> item) {}

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        };
    }
}
