package com.defigestuel.compreface;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.defigestuel.compreface.FaceRecognizer.Recognition;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Le client CompreFace contre un vrai serveur HTTP local qui imite les réponses de CompreFace 1.2. */
class CompreFaceRecognizerTest {

    private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4 };

    private HttpServer server;
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<HttpExchange> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/recognition/recognize", exchange -> {
            lastRequest.set(exchange);
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private CompreFaceRecognizer recognizer(String apiKey) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new CompreFaceRecognizer(new CompreFaceProperties(url, apiKey, 0.8, Duration.ofSeconds(5)), RestClient.builder());
    }

    @Test
    void theLargestFaceAndItsBestSubjectAreKept() {
        body.set("""
                {"result":[
                  {"box":{"probability":0.99,"x_min":10,"y_min":10,"x_max":40,"y_max":40},
                   "subjects":[{"subject":"bruno","similarity":0.91}]},
                  {"box":{"probability":0.99,"x_min":100,"y_min":50,"x_max":300,"y_max":300},
                   "subjects":[{"subject":"alice","similarity":0.97},{"subject":"carla","similarity":0.40}]}
                ],"plugins_versions":{"detector":"facenet.FaceDetector"}}""");
        Recognition r = recognizer("00000000-cle-0000-0000-000000000001").recognize(JPEG, "image/jpeg");
        assertThat(r).isEqualTo(new Recognition(2, "alice", 0.97));

        HttpExchange request = lastRequest.get();
        assertThat(request.getRequestMethod()).isEqualTo("POST");
        assertThat(request.getRequestHeaders().getFirst("x-api-key")).isEqualTo("00000000-cle-0000-0000-000000000001");
        assertThat(request.getRequestURI().getQuery()).contains("limit=0", "prediction_count=1", "det_prob_threshold=0.8");
        assertThat(request.getRequestHeaders().getFirst("Content-Type")).startsWith("multipart/form-data");
        assertThat(lastBody.get()).contains("name=\"file\"", "filename=\"photo.jpg\"", "Content-Type: image/jpeg");
    }

    @Test
    void aFaceWithoutSubjectsMeansAnEmptyCollection() {
        body.set("{\"result\":[{\"box\":{\"probability\":0.99,\"x_min\":0,\"y_min\":0,\"x_max\":10,\"y_max\":10},\"subjects\":[]}]}");
        assertThat(recognizer("k").recognize(JPEG, "image/jpeg")).isEqualTo(new Recognition(1, null, null));
    }

    @Test
    void noFaceIsNotAnOutage() {
        status.set(400);
        body.set("{\"message\":\"No face is found in the given image\",\"code\":28}");
        assertThat(recognizer("k").recognize(JPEG, "image/jpeg")).isEqualTo(Recognition.noFace());
    }

    @Test
    void aWrongKeyOrAnOutageIsAServiceError() throws IOException {
        status.set(401);
        body.set("{\"message\":\"Missing header: x-api-key\",\"code\":20}");
        assertThatThrownBy(() -> recognizer("k").recognize(JPEG, "image/jpeg"))
                .isInstanceOf(FaceServiceException.class).hasMessageContaining("401");

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var down = new CompreFaceRecognizer(new CompreFaceProperties("http://127.0.0.1:" + closedPort, "k", 0.8,
                Duration.ofSeconds(2)), RestClient.builder());
        assertThatThrownBy(() -> down.recognize(JPEG, "image/jpeg"))
                .isInstanceOf(FaceServiceException.class).hasMessageContaining("ne répond pas");
    }

    @Test
    void withoutAKeyNothingIsSent() {
        assertThatThrownBy(() -> recognizer("").recognize(JPEG, "image/jpeg"))
                .isInstanceOf(FaceServiceException.class).hasMessageContaining("pas réglé");
        assertThat(lastRequest.get()).isNull();
    }
}
