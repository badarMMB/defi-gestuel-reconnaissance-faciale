package com.defigestuel.challenge;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Un défi : un code de 3 chiffres, à montrer avec les doigts, un chiffre par étape, devant la caméra. Les méthodes ne
 * sont pas synchronisées : le service verrouille le défi pendant tout le traitement d'une étape.
 */
public final class Challenge {

    public static final int STEPS = 3;

    public enum Status { IN_PROGRESS, APPROVED, REJECTED }

    /** Le résultat d'une étape : le chiffre attendu et montré, et ce que la reconnaissance faciale a trouvé. */
    public record StepResult(int step, int expectedDigit, int detectedDigit, int faceCount, String subject,
            Double similarity, Instant at) {
    }

    private final UUID id;
    private final String code;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final List<StepResult> steps = new ArrayList<>(STEPS);
    private final List<String> reasons = new ArrayList<>();
    private Status status = Status.IN_PROGRESS;
    private Instant finishedAt;
    private String subject;

    Challenge(UUID id, String code, Instant createdAt, Duration ttl) {
        if (code == null || !code.matches("\\d{" + STEPS + "}")) {
            throw new IllegalArgumentException("le code doit compter " + STEPS + " chiffres");
        }
        this.id = id;
        this.code = code;
        this.createdAt = createdAt;
        this.expiresAt = createdAt.plus(ttl);
    }

    public UUID id() {
        return id;
    }

    String code() {
        return code;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Status status() {
        return status;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public String subject() {
        return subject;
    }

    public List<StepResult> steps() {
        return List.copyOf(steps);
    }

    public List<String> reasons() {
        return List.copyOf(reasons);
    }

    public boolean finished() {
        return status != Status.IN_PROGRESS;
    }

    public boolean expired(Instant now) {
        return now.isAfter(expiresAt);
    }

    /** L'étape attendue (1 à 3), ou 0 si les trois sont faites. */
    public int nextStep() {
        return steps.size() < STEPS ? steps.size() + 1 : 0;
    }

    /** Le chiffre à montrer à une étape (1 à 3). */
    public int digit(int step) {
        if (step < 1 || step > STEPS) {
            throw new IllegalArgumentException("étape hors limites : " + step);
        }
        return code.charAt(step - 1) - '0';
    }

    void record(StepResult result) {
        if (finished() || result.step() != nextStep()) {
            throw new IllegalStateException("étape " + result.step() + " inattendue");
        }
        steps.add(result);
    }

    void approve(String subject, Instant now) {
        this.subject = subject;
        finish(Status.APPROVED, now);
    }

    void reject(List<String> why, Instant now) {
        reasons.addAll(why);
        finish(Status.REJECTED, now);
    }

    private void finish(Status status, Instant now) {
        if (finished()) {
            throw new IllegalStateException("défi déjà terminé");
        }
        this.status = status;
        this.finishedAt = now;
    }
}
