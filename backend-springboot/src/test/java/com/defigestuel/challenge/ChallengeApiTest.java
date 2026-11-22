package com.defigestuel.challenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.defigestuel.compreface.FaceRecognizer;
import com.defigestuel.compreface.FaceServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Le parcours complet d'un défi par l'API, avec un faux CompreFace : la « photo » est un en-tête JPEG suivi d'un texte
 * qui dit ce que le faux moteur doit répondre (« alice:0.97 », « aucun », « deux », « panne »), et une horloge réglable.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChallengeApiTest {

    @TestConfiguration
    static class Fakes {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        FakeRecognizer fakeRecognizer() {
            return new FakeRecognizer();
        }
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-22T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class FakeRecognizer implements FaceRecognizer {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Recognition recognize(byte[] image, String contentType) {
            calls.incrementAndGet();
            String text = new String(image, 4, image.length - 4, StandardCharsets.UTF_8);
            return switch (text) {
                case "aucun" -> Recognition.noFace();
                case "deux" -> new Recognition(2, "alice", 0.97);
                case "inconnu" -> new Recognition(1, null, null);
                case "panne" -> throw new FaceServiceException("CompreFace arrêté");
                default -> {
                    String[] parts = text.split(":");
                    yield new Recognition(1, parts[0], Double.parseDouble(parts[1]));
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    MutableClock clock;
    @Autowired
    FakeRecognizer recognizer;
    @Autowired
    ChallengeStore store;

    @BeforeEach
    void reset() {
        recognizer.calls.set(0);
    }

    @Test
    void theSamePersonShowingTheThreeDigitsIsApproved() throws Exception {
        JsonNode created = create();
        assertThat(created.get("step").asInt()).isEqualTo(1);
        assertThat(created.get("totalSteps").asInt()).isEqualTo(3);
        assertThat(created.get("expiresInSeconds").asLong()).isEqualTo(45);
        String id = created.get("challengeId").asText();

        int digit = created.get("targetDigit").asInt();
        clock.advance(Duration.ofSeconds(2));
        JsonNode second = read(step(id, 1, digit, "alice:0.97").andExpect(status().isOk())
                .andExpect(jsonPath("$.completedStep").value(1))
                .andExpect(jsonPath("$.step").value(2))
                .andExpect(jsonPath("$.expiresInSeconds").value(43))
                .andExpect(jsonPath("$.verdict").doesNotExist()));
        clock.advance(Duration.ofSeconds(2));
        JsonNode third = read(step(id, 2, second.get("targetDigit").asInt(), "alice:0.93").andExpect(jsonPath("$.step").value(3)));
        clock.advance(Duration.ofSeconds(2));
        step(id, 3, third.get("targetDigit").asInt(), "alice:0.99").andExpect(status().isOk())
                .andExpect(jsonPath("$.completedStep").value(3))
                .andExpect(jsonPath("$.step").doesNotExist())
                .andExpect(jsonPath("$.verdict.decision").value("APPROVED"))
                .andExpect(jsonPath("$.verdict.subject").value("alice"))
                .andExpect(jsonPath("$.verdict.reasons", hasSize(0)))
                .andExpect(jsonPath("$.verdict.steps", hasSize(3)))
                .andExpect(jsonPath("$.verdict.steps[1].similarity").value(0.93))
                .andExpect(jsonPath("$.verdict.durationMillis").value(6000));

        // Usage unique : le défi terminé ne prend plus d'étape, mais son verdict se relit.
        step(id, 3, third.get("targetDigit").asInt(), "alice:0.99").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("déjà terminé")));
        mvc.perform(get("/api/v1/challenges/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.verdict.decision").value("APPROVED"));
        assertThat(recognizer.calls.get()).isEqualTo(3);
    }

    @Test
    void aWrongDigitRejectsAtOnceWithoutAskingCompreFace() throws Exception {
        JsonNode created = create();
        String id = created.get("challengeId").asText();
        int wrong = (created.get("targetDigit").asInt() + 1) % 10;
        clock.advance(Duration.ofSeconds(3));
        step(id, 1, wrong, "alice:0.99").andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict.decision").value("REJECTED"))
                .andExpect(jsonPath("$.verdict.reasons[0]", containsString("au lieu de " + created.get("targetDigit").asInt())));
        assertThat(recognizer.calls.get()).isZero();
        step(id, 2, 0, "alice:0.99").andExpect(status().isConflict());
    }

    @Test
    void differentPersonsAreRejected() throws Exception {
        rejectedAfterThreeSteps(new String[] { "alice:0.97", "bruno:0.96", "alice:0.98" })
                .andExpect(jsonPath("$.verdict.reasons", hasItem(containsString("pas la même personne : alice, bruno"))));
    }

    @Test
    void aSimilarityUnderTheThresholdIsRejected() throws Exception {
        rejectedAfterThreeSteps(new String[] { "alice:0.97", "alice:0.84", "alice:0.98" })
                .andExpect(jsonPath("$.verdict.reasons", hasSize(1)))
                .andExpect(jsonPath("$.verdict.reasons[0]", containsString("Étape 2 : ressemblance 84,0 % avec alice")));
    }

    @Test
    void anUnknownPersonIsRejected() throws Exception {
        rejectedAfterThreeSteps(new String[] { "alice:0.97", "inconnu", "alice:0.98" })
                .andExpect(jsonPath("$.verdict.reasons[0]", containsString("Étape 2 : aucune personne de la collection reconnue")));
    }

    @Test
    void threeStepsInLessThanTwoSecondsAreAMachine() throws Exception {
        JsonNode created = create();
        String id = created.get("challengeId").asText();
        JsonNode r = read(step(id, 1, created.get("targetDigit").asInt(), "alice:0.99"));
        r = read(step(id, 2, r.get("targetDigit").asInt(), "alice:0.99"));
        clock.advance(Duration.ofMillis(1500));
        step(id, 3, r.get("targetDigit").asInt(), "alice:0.99")
                .andExpect(jsonPath("$.verdict.decision").value("REJECTED"))
                .andExpect(jsonPath("$.verdict.reasons[0]", containsString("trop vite")));
    }

    @Test
    void anExpiredChallengeIsRejected() throws Exception {
        JsonNode created = create();
        String id = created.get("challengeId").asText();
        clock.advance(Duration.ofSeconds(3));
        JsonNode r = read(step(id, 1, created.get("targetDigit").asInt(), "alice:0.99"));
        clock.advance(Duration.ofSeconds(43));
        step(id, 2, r.get("targetDigit").asInt(), "alice:0.99").andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict.decision").value("REJECTED"))
                .andExpect(jsonPath("$.verdict.reasons[0]").value("Délai de 45 secondes dépassé"));
        assertThat(recognizer.calls.get()).isEqualTo(1);
    }

    @Test
    void aPhotoWithoutAFaceOrWithTwoFacesRejects() throws Exception {
        JsonNode created = create();
        clock.advance(Duration.ofSeconds(3));
        step(created.get("challengeId").asText(), 1, created.get("targetDigit").asInt(), "aucun")
                .andExpect(jsonPath("$.verdict.decision").value("REJECTED"))
                .andExpect(jsonPath("$.verdict.reasons[0]").value("Étape 1 : aucun visage sur la photo"));

        created = create();
        step(created.get("challengeId").asText(), 1, created.get("targetDigit").asInt(), "deux")
                .andExpect(jsonPath("$.verdict.decision").value("REJECTED"))
                .andExpect(jsonPath("$.verdict.reasons[0]", containsString("2 visages")));
    }

    @Test
    void aCompreFaceOutageDoesNotCountTheStep() throws Exception {
        JsonNode created = create();
        String id = created.get("challengeId").asText();
        int digit = created.get("targetDigit").asInt();
        clock.advance(Duration.ofSeconds(1));
        step(id, 1, digit, "panne").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", containsString("n'est pas comptée")));
        step(id, 1, digit, "alice:0.97").andExpect(status().isOk()).andExpect(jsonPath("$.step").value(2));
    }

    @Test
    void badRequestsDoNotTouchTheChallenge() throws Exception {
        JsonNode created = create();
        String id = created.get("challengeId").asText();
        int digit = created.get("targetDigit").asInt();
        step(id, 2, 0, "alice:0.97").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Étape attendue : 1, reçue : 2"));
        step(id, 4, 0, "alice:0.97").andExpect(status().isBadRequest());
        step(id, 1, 10, "alice:0.97").andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/challenges/" + id + "/steps")
                .file(new MockMultipartFile("image", "x.gif", "image/gif", "GIF89a-pas-une-photo".getBytes()))
                .param("step", "1").param("detectedDigit", String.valueOf(digit)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("JPEG ou un PNG")));
        mvc.perform(multipart("/api/v1/challenges/" + id + "/steps").param("step", "1").param("detectedDigit", "1"))
                .andExpect(status().isBadRequest());
        step(UUID.randomUUID().toString(), 1, 0, "alice:0.97").andExpect(status().isNotFound());
        assertThat(recognizer.calls.get()).isZero();
        mvc.perform(get("/api/v1/challenges/" + id)).andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.step").value(1)).andExpect(jsonPath("$.targetDigit").value(digit));
    }

    @Test
    void theAngularOriginIsAllowedAndOthersAreNot() throws Exception {
        mvc.perform(options("/api/v1/challenges").header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
        mvc.perform(options("/api/v1/challenges").header("Origin", "http://pirate.example")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void finishedAndExpiredChallengesAreForgottenAfterTheRetention() throws Exception {
        String id = create().get("challengeId").asText();
        clock.advance(Duration.ofSeconds(45 + 299));
        store.removeStale();
        assertThat(store.find(UUID.fromString(id))).isPresent();
        clock.advance(Duration.ofSeconds(2));
        store.removeStale();
        assertThat(store.find(UUID.fromString(id))).isEmpty();
        mvc.perform(get("/api/v1/challenges/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void theCodeIsAlwaysThreeDigits() {
        for (int i = 0; i < 200; i++) {
            Challenge c = new Challenge(UUID.randomUUID(), String.format("%03d", i % 1000), Instant.EPOCH, Duration.ofSeconds(45));
            assertThat(c.digit(1) * 100 + c.digit(2) * 10 + c.digit(3)).isEqualTo(i);
        }
        assertThat(ChallengeService.imageType(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0 }, null)).isEqualTo("image/png");
    }

    private ResultActions rejectedAfterThreeSteps(String[] photos) throws Exception {
        JsonNode r = create();
        String id = r.get("challengeId").asText();
        clock.advance(Duration.ofSeconds(3));
        for (int i = 0; i < 2; i++) {
            r = read(step(id, i + 1, r.get("targetDigit").asInt(), photos[i]).andExpect(jsonPath("$.verdict").doesNotExist()));
        }
        return step(id, 3, r.get("targetDigit").asInt(), photos[2]).andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict.decision").value("REJECTED"));
    }

    private JsonNode create() throws Exception {
        return read(mvc.perform(post("/api/v1/challenges")).andExpect(status().isCreated()));
    }

    private ResultActions step(String id, int step, int digit, String photo) throws Exception {
        // Le texte après l'en-tête JPEG est relu en UTF-8 par le faux moteur.
        byte[] text = photo.getBytes(StandardCharsets.UTF_8);
        byte[] image = new byte[4 + text.length];
        image[0] = (byte) 0xFF;
        image[1] = (byte) 0xD8;
        image[2] = (byte) 0xFF;
        image[3] = (byte) 0xE0;
        System.arraycopy(text, 0, image, 4, text.length);
        if (image.length < 8) {
            image = java.util.Arrays.copyOf(image, 8);
        }
        return mvc.perform(multipart("/api/v1/challenges/" + id + "/steps")
                .file(new MockMultipartFile("image", "photo.jpg", MediaType.IMAGE_JPEG_VALUE, image))
                .param("step", String.valueOf(step))
                .param("detectedDigit", String.valueOf(digit)));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
