package com.defigestuel.web;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.defigestuel.challenge.ChallengeDtos.Created;
import com.defigestuel.challenge.ChallengeDtos.State;
import com.defigestuel.challenge.ChallengeDtos.StepResponse;
import com.defigestuel.challenge.ChallengeService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** L'API des défis : créer un défi, envoyer chaque étape, relire l'état. */
@RestController
@RequestMapping("/api/v1/challenges")
@Validated
public class ChallengeController {

    private final ChallengeService service;

    public ChallengeController(ChallengeService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Created create() {
        return service.create();
    }

    @GetMapping("/{challengeId}")
    public State state(@PathVariable UUID challengeId) {
        return service.state(challengeId);
    }

    @PostMapping(path = "/{challengeId}/steps", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StepResponse step(@PathVariable UUID challengeId,
            @RequestParam @Min(1) @Max(3) int step,
            @RequestParam @Min(0) @Max(9) int detectedDigit,
            @RequestPart MultipartFile image) throws IOException {
        return service.submitStep(challengeId, step, detectedDigit, image.getBytes(), image.getContentType());
    }
}
