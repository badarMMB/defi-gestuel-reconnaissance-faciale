import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_URL } from '../config';

export interface Created {
  challengeId: string;
  step: number;
  targetDigit: number;
  expiresInSeconds: number;
  totalSteps: number;
}

export interface StepDetail {
  step: number;
  expectedDigit: number;
  detectedDigit: number;
  faceCount: number;
  subject?: string;
  similarity?: number;
}

export interface Verdict {
  decision: 'APPROVED' | 'REJECTED';
  subject?: string;
  reasons: string[];
  steps: StepDetail[];
  durationMillis: number;
}

export interface StepResponse {
  challengeId: string;
  completedStep: number;
  step?: number;
  targetDigit?: number;
  expiresInSeconds: number;
  verdict?: Verdict;
}

/** L'API des défis du backend Spring Boot. */
@Injectable({ providedIn: 'root' })
export class ChallengeApi {
  private readonly http = inject(HttpClient);

  create(): Observable<Created> {
    return this.http.post<Created>(`${API_URL}/api/v1/challenges`, null);
  }

  submitStep(challengeId: string, step: number, detectedDigit: number, image: Blob): Observable<StepResponse> {
    const form = new FormData();
    form.append('step', String(step));
    form.append('detectedDigit', String(detectedDigit));
    form.append('image', image, `etape-${step}.jpg`);
    return this.http.post<StepResponse>(`${API_URL}/api/v1/challenges/${encodeURIComponent(challengeId)}/steps`, form);
  }
}

/** Le message d'erreur lisible d'une réponse du backend (format problem+json), et si l'étape peut être refaite. */
export function describeError(error: unknown): { message: string; retry: boolean } {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return { message: 'Le serveur ne répond pas : vérifiez qu\'il est démarré.', retry: true };
    }
    const detail = typeof error.error?.detail === 'string' ? error.error.detail : error.message;
    return { message: detail, retry: error.status === 503 || error.status === 400 || error.status === 413 };
  }
  return { message: String(error), retry: true };
}
