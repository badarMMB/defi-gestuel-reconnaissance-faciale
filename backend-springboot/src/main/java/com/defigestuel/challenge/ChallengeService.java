package com.defigestuel.challenge;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.defigestuel.challenge.ChallengeDtos.Created;
import com.defigestuel.challenge.ChallengeDtos.Decision;
import com.defigestuel.challenge.ChallengeDtos.State;
import com.defigestuel.challenge.ChallengeDtos.StepDetail;
import com.defigestuel.challenge.ChallengeDtos.StepResponse;
import com.defigestuel.challenge.ChallengeDtos.Verdict;
import com.defigestuel.compreface.FaceRecognizer;
import com.defigestuel.compreface.FaceRecognizer.Recognition;

/**
 * Le cycle de vie d'un défi.
 * <ol>
 * <li>Création : un code de 3 chiffres tiré au hasard (000 à 999), valable 45 secondes.</li>
 * <li>Chaque étape : le chiffre montré doit être celui attendu, puis la photo part à CompreFace. Un chiffre faux, une
 * photo sans visage ou avec plusieurs visages, ou un délai dépassé, rejettent le défi aussitôt : un défi ne sert
 * qu'une fois, on ne retente pas sur le même code.</li>
 * <li>Après la 3e étape : accepté seulement si les 3 photos reconnaissent la même personne, chacune avec une
 * ressemblance d'au moins 85 %, entre 2 et 45 secondes après la création.</li>
 * </ol>
 * Une panne de CompreFace ne rejette pas le défi : l'étape n'est pas comptée et peut être refaite tant que le délai
 * court. Tout le traitement d'une étape se fait sous le verrou du défi : deux envois simultanés de la même étape ne
 * peuvent pas être comptés deux fois.
 */
@Service
public class ChallengeService {

    private static final Logger LOG = LoggerFactory.getLogger(ChallengeService.class);

    private final ChallengeStore store;
    private final FaceRecognizer recognizer;
    private final ChallengeProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ChallengeService(ChallengeStore store, FaceRecognizer recognizer, ChallengeProperties properties, Clock clock) {
        this.store = store;
        this.recognizer = recognizer;
        this.properties = properties;
        this.clock = clock;
    }

    public Created create() {
        String code = String.format(Locale.ROOT, "%03d", random.nextInt(1000));
        Instant now = clock.instant();
        Challenge challenge = store.add(new Challenge(UUID.randomUUID(), code, now, properties.ttl()));
        LOG.info("Défi {} créé", challenge.id());
        return new Created(challenge.id(), 1, challenge.digit(1), remaining(challenge, now), Challenge.STEPS);
    }

    public State state(UUID id) {
        Challenge challenge = find(id);
        synchronized (challenge) {
            Instant now = clock.instant();
            expireIfNeeded(challenge, now);
            int next = challenge.nextStep();
            boolean open = !challenge.finished() && next > 0;
            return new State(challenge.id(), challenge.status().name(), open ? next : null,
                    open ? challenge.digit(next) : null, remaining(challenge, now),
                    challenge.finished() ? verdict(challenge) : null);
        }
    }

    public StepResponse submitStep(UUID id, int step, int detectedDigit, byte[] image, String contentType) {
        Challenge challenge = find(id);
        synchronized (challenge) {
            Instant now = clock.instant();
            if (challenge.finished()) {
                throw new ChallengeException.Conflict("Ce défi est déjà terminé (" + label(challenge.status()) + ") : demandez-en un nouveau");
            }
            if (expireIfNeeded(challenge, now)) {
                return response(challenge, step, now);
            }
            if (step != challenge.nextStep()) {
                throw new ChallengeException.Conflict("Étape attendue : " + challenge.nextStep() + ", reçue : " + step);
            }
            String type = imageType(image, contentType);
            int expected = challenge.digit(step);
            if (detectedDigit != expected) {
                challenge.record(new Challenge.StepResult(step, expected, detectedDigit, 0, null, null, now));
                challenge.reject(List.of("Étape " + step + " : " + detectedDigit + " doigt(s) montré(s) au lieu de " + expected), now);
                LOG.info("Défi {} rejeté : mauvais chiffre à l'étape {}", id, step);
                return response(challenge, step, now);
            }
            // Peut lever FaceServiceException : l'étape n'est alors pas comptée.
            Recognition recognition = recognizer.recognize(image, type);
            now = clock.instant();
            challenge.record(new Challenge.StepResult(step, expected, detectedDigit, recognition.faceCount(),
                    recognition.subject(), recognition.similarity(), now));
            LOG.info("Défi {} étape {} : {} visage(s), sujet {}, ressemblance {}", id, step, recognition.faceCount(),
                    recognition.subject(), recognition.similarity());
            List<String> early = earlyFailures(step, recognition, challenge, now);
            if (!early.isEmpty()) {
                challenge.reject(early, now);
                return response(challenge, step, now);
            }
            if (step == Challenge.STEPS) {
                decide(challenge, now);
            }
            return response(challenge, step, now);
        }
    }

    /** Ce qui rend le défi perdu sans attendre la fin : un visage absent ou en trop, un délai dépassé. */
    private List<String> earlyFailures(int step, Recognition recognition, Challenge challenge, Instant now) {
        List<String> reasons = new ArrayList<>();
        if (recognition.faceCount() == 0) {
            reasons.add("Étape " + step + " : aucun visage sur la photo");
        } else if (recognition.faceCount() > 1) {
            reasons.add("Étape " + step + " : " + recognition.faceCount() + " visages sur la photo, un seul est permis");
        }
        if (challenge.expired(now)) {
            reasons.add(expiredReason());
        }
        return reasons;
    }

    private void decide(Challenge challenge, Instant now) {
        List<String> reasons = new ArrayList<>();
        Duration elapsed = Duration.between(challenge.createdAt(), now);
        if (elapsed.compareTo(properties.ttl()) > 0) {
            reasons.add(expiredReason());
        }
        if (elapsed.compareTo(properties.minDuration()) < 0) {
            reasons.add("Défi réalisé en " + elapsed.toMillis() + " ms : trop vite pour un humain (minimum "
                    + properties.minDuration().toMillis() + " ms)");
        }
        List<Challenge.StepResult> steps = challenge.steps();
        for (Challenge.StepResult s : steps) {
            if (s.detectedDigit() != s.expectedDigit()) {
                reasons.add("Étape " + s.step() + " : chiffre " + s.detectedDigit() + " au lieu de " + s.expectedDigit());
            }
            if (s.subject() == null) {
                reasons.add("Étape " + s.step() + " : aucune personne de la collection reconnue");
            } else if (s.similarity() == null || s.similarity() < properties.minSimilarity()) {
                reasons.add(String.format(Locale.FRANCE, "Étape %d : ressemblance %.1f %% avec %s, en dessous du seuil de %.1f %%",
                        s.step(), percent(s.similarity()), s.subject(), properties.minSimilarity() * 100));
            }
        }
        List<String> subjects = steps.stream().map(Challenge.StepResult::subject).filter(Objects::nonNull).distinct().toList();
        if (subjects.size() > 1) {
            reasons.add("Les photos ne montrent pas la même personne : " + String.join(", ", subjects));
        }
        if (reasons.isEmpty()) {
            challenge.approve(subjects.getFirst(), now);
            LOG.info("Défi {} accepté : {} en {} ms", challenge.id(), subjects.getFirst(), elapsed.toMillis());
        } else {
            challenge.reject(reasons, now);
            LOG.info("Défi {} rejeté : {}", challenge.id(), reasons);
        }
    }

    private boolean expireIfNeeded(Challenge challenge, Instant now) {
        if (!challenge.finished() && challenge.expired(now)) {
            challenge.reject(List.of(expiredReason()), now);
            LOG.info("Défi {} expiré", challenge.id());
            return true;
        }
        return false;
    }

    private String expiredReason() {
        return "Délai de " + properties.ttl().toSeconds() + " secondes dépassé";
    }

    private StepResponse response(Challenge challenge, int completedStep, Instant now) {
        if (challenge.finished()) {
            return new StepResponse(challenge.id(), completedStep, null, null, 0, verdict(challenge));
        }
        int next = challenge.nextStep();
        return new StepResponse(challenge.id(), completedStep, next, challenge.digit(next), remaining(challenge, now), null);
    }

    private Verdict verdict(Challenge challenge) {
        List<StepDetail> details = challenge.steps().stream()
                .map(s -> new StepDetail(s.step(), s.expectedDigit(), s.detectedDigit(), s.faceCount(), s.subject(), s.similarity()))
                .toList();
        Decision decision = challenge.status() == Challenge.Status.APPROVED ? Decision.APPROVED : Decision.REJECTED;
        long duration = Duration.between(challenge.createdAt(), challenge.finishedAt()).toMillis();
        return new Verdict(decision, challenge.subject(), challenge.reasons(), details, duration);
    }

    private long remaining(Challenge challenge, Instant now) {
        long millis = Duration.between(now, challenge.expiresAt()).toMillis();
        return Math.max(0, (millis + 999) / 1000);
    }

    private Challenge find(UUID id) {
        return store.find(id).orElseThrow(() -> new ChallengeException.NotFound(id));
    }

    private static double percent(Double similarity) {
        return similarity == null ? 0 : similarity * 100;
    }

    private static String label(Challenge.Status status) {
        return switch (status) {
            case APPROVED -> "accepté";
            case REJECTED -> "rejeté";
            case IN_PROGRESS -> "en cours";
        };
    }

    /** Le type réel de la photo, lu dans ses premiers octets : on ne se fie pas au type annoncé par le client. */
    static String imageType(byte[] image, String declared) {
        if (image == null || image.length < 8) {
            throw new ChallengeException.InvalidImage("La photo est vide ou tronquée");
        }
        if ((image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8 && (image[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if ((image[0] & 0xFF) == 0x89 && image[1] == 'P' && image[2] == 'N' && image[3] == 'G') {
            return "image/png";
        }
        throw new ChallengeException.InvalidImage("La photo doit être un JPEG ou un PNG (type annoncé : " + declared + ")");
    }
}
