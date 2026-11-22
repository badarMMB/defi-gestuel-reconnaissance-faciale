package com.defigestuel.challenge;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Les réponses de l'API des défis. */
public final class ChallengeDtos {

    private ChallengeDtos() {
    }

    public enum Decision { APPROVED, REJECTED }

    /** Un défi créé : le premier chiffre à montrer. Les deux suivants ne sont donnés qu'au fil des étapes. */
    public record Created(UUID challengeId, int step, int targetDigit, long expiresInSeconds, int totalSteps) {
    }

    /**
     * La réponse à une étape. Tant que le défi est en cours, elle donne l'étape et le chiffre suivants ; à la fin
     * (dernière étape, ou refus anticipé), elle porte le verdict.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepResponse(UUID challengeId, int completedStep, Integer step, Integer targetDigit,
            long expiresInSeconds, Verdict verdict) {
    }

    /** L'état d'un défi, pour le relire (par exemple après une coupure réseau). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record State(UUID challengeId, String status, Integer step, Integer targetDigit, long expiresInSeconds,
            Verdict verdict) {
    }

    /**
     * Le verdict final. Les ressemblances sont rendues pour l'essai du prototype ; un système réel ne les montrerait
     * qu'aux agents, pas à la caisse.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Verdict(Decision decision, String subject, List<String> reasons, List<StepDetail> steps,
            long durationMillis) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepDetail(int step, int expectedDigit, int detectedDigit, int faceCount, String subject,
            Double similarity) {
    }
}
