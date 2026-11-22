package com.defigestuel.compreface;

import java.net.http.HttpClient;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * L'appel au service de reconnaissance CompreFace : {@code POST /api/v1/recognition/recognize}, avec la clé du service
 * dans l'en-tête {@code x-api-key}. CompreFace répond 400 (code 28) quand la photo ne contient aucun visage : ce n'est
 * pas une panne, la photo est simplement inutilisable.
 */
@Component
public class CompreFaceRecognizer implements FaceRecognizer {

    private static final Logger LOG = LoggerFactory.getLogger(CompreFaceRecognizer.class);
    private static final int NO_FACE_CODE = 28;

    private final CompreFaceProperties properties;
    private final RestClient client;

    public CompreFaceRecognizer(CompreFaceProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        var http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.timeout());
        this.client = builder.requestFactory(factory).baseUrl(Objects.requireNonNullElse(properties.url(), "")).build();
    }

    @Override
    public Recognition recognize(byte[] image, String contentType) {
        if (!properties.configured()) {
            throw new FaceServiceException("CompreFace n'est pas réglé : renseignez compreface.url et compreface.api-key");
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", part(image, contentType));
        Response response;
        try {
            response = client.post()
                    .uri(u -> u.path("/api/v1/recognition/recognize")
                            .queryParam("limit", 0)
                            .queryParam("prediction_count", 1)
                            .queryParam("det_prob_threshold", properties.detProbThreshold())
                            .build())
                    .header("x-api-key", properties.apiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Response.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 400 && isNoFace(e)) {
                return Recognition.noFace();
            }
            LOG.warn("CompreFace a refusé la requête : {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new FaceServiceException("Le service de reconnaissance a refusé la requête (" + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            LOG.warn("CompreFace ne répond pas : {}", e.getMessage());
            throw new FaceServiceException("Le service de reconnaissance ne répond pas", e);
        }
        List<Face> faces = response == null || response.result() == null ? List.of() : response.result();
        if (faces.isEmpty()) {
            return Recognition.noFace();
        }
        // Le visage principal : le plus grand, donc le plus proche de la caméra.
        Face main = faces.stream().max(Comparator.comparingDouble(Face::area)).orElseThrow();
        Subject best = main.subjects() == null ? null
                : main.subjects().stream().max(Comparator.comparingDouble(Subject::similarity)).orElse(null);
        return best == null ? new Recognition(faces.size(), null, null)
                : new Recognition(faces.size(), best.subject(), best.similarity());
    }

    private static HttpEntity<ByteArrayResource> part(byte[] image, String contentType) {
        var headers = new HttpHeaders();
        headers.setContentType(contentType == null ? MediaType.IMAGE_JPEG : MediaType.parseMediaType(contentType));
        String filename = MediaType.IMAGE_PNG_VALUE.equals(contentType) ? "photo.png" : "photo.jpg";
        return new HttpEntity<>(new ByteArrayResource(image) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, headers);
    }

    private static boolean isNoFace(HttpClientErrorException e) {
        try {
            var error = e.getResponseBodyAs(Error.class);
            if (error != null && error.code() != null) {
                return error.code() == NO_FACE_CODE;
            }
        } catch (RuntimeException ignored) {
            // corps illisible : on se rabat sur le texte
        }
        return e.getResponseBodyAsString().toLowerCase().contains("no face");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<Face> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Face(Box box, List<Subject> subjects) {

        double area() {
            return box == null ? 0 : (double) (box.xMax() - box.xMin()) * (box.yMax() - box.yMin());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Box(double probability,
            @JsonProperty("x_min") int xMin,
            @JsonProperty("y_min") int yMin,
            @JsonProperty("x_max") int xMax,
            @JsonProperty("y_max") int yMax) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Subject(String subject, double similarity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Error(String message, Integer code) {
    }
}
