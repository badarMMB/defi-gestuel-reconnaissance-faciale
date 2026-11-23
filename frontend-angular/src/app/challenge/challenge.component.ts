import { Component, ElementRef, OnDestroy, computed, inject, isDevMode, signal, viewChild } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { HAND_CONNECTIONS, Frame, Vision } from '../vision/vision';
import type { Region } from '../vision/face-search';
import { ChallengeApi, Verdict, describeError } from './challenge-api';

const DETECTION_INTERVAL_MS = 66;
/**
 * Validation automatique : le bon chiffre doit être lu sans interruption pendant ce temps avant que la photo parte.
 * Un comptage qui clignote une image sur deux ne déclenche donc rien, et la photo prise est celle d'une pose tenue.
 */
const HOLD_MS = 700;
/**
 * Un visage plus étroit que cette part de la largeur de l'image (32 pixels sur 1280) est trop loin : CompreFace le
 * trouverait encore, mais le reconnaîtrait mal (mesuré : ressemblance sous 85 % en dessous de 25 pixels environ).
 */
const MIN_FACE_WIDTH_RATIO = 0.025;

type Phase = 'idle' | 'loading' | 'creating' | 'running' | 'sending' | 'done';

interface DoneStep {
  step: number;
  digit: number;
  photo: string;
}

/**
 * Le défi gestuel : le serveur tire un code de 3 chiffres ; pour chaque chiffre, la personne le montre avec les doigts
 * de ses deux mains, le visage dans l'image ; MediaPipe compte les doigts dans le navigateur ; une photo de l'image
 * entière part au serveur, qui vérifie le chiffre et fait reconnaître le visage par CompreFace.
 *
 * La photo part toute seule dès que le chiffre demandé est montré et tenu {@link HOLD_MS} ms, avec exactement un visage
 * et deux mains. Si le chiffre suivant est le même, il faut d'abord changer de pose : une même pose ne valide jamais deux
 * étapes. Le bouton reste là pour valider à la main, ou quand la validation automatique est coupée.
 */
@Component({
  selector: 'app-challenge',
  templateUrl: './challenge.component.html',
  styleUrl: './challenge.component.css',
})
export class ChallengeComponent implements OnDestroy {
  private readonly api = inject(ChallengeApi);
  private readonly video = viewChild.required<ElementRef<HTMLVideoElement>>('video');
  private readonly overlay = viewChild.required<ElementRef<HTMLCanvasElement>>('overlay');

  protected readonly phase = signal<Phase>('idle');
  protected readonly frame = signal<Frame | null>(null);
  protected readonly challengeId = signal<string | null>(null);
  protected readonly step = signal(0);
  protected readonly totalSteps = signal(3);
  protected readonly targetDigit = signal<number | null>(null);
  protected readonly expiresAt = signal(0);
  protected readonly ttl = signal(45);
  protected readonly now = signal(Date.now());
  protected readonly doneSteps = signal<DoneStep[]>([]);
  protected readonly verdict = signal<Verdict | null>(null);
  protected readonly error = signal('');
  protected readonly cameraOn = signal(false);
  protected readonly delegate = signal('');
  /** Combien d'analyses par seconde la machine arrive à faire, mesuré sur la dernière seconde. */
  protected readonly rate = signal(0);
  protected readonly autoMode = signal(true);
  /** Où en est la pose tenue, de 0 à 1 ; à 1, la photo part. */
  protected readonly holdProgress = signal(0);
  /** Le bon chiffre est montré, mais c'est encore la pose de l'étape précédente : il faut la défaire d'abord. */
  protected readonly waitingNewPose = signal(false);

  protected readonly faceCount = computed(() => this.frame()?.faces.length ?? 0);
  protected readonly handCount = computed(() => this.frame()?.hands.length ?? 0);
  protected readonly digit = computed(() => this.frame()?.reading.digit ?? null);
  protected readonly remaining = computed(() => Math.max(0, Math.ceil((this.expiresAt() - this.now()) / 1000)));
  protected readonly remainingRatio = computed(() => Math.min(1, Math.max(0, (this.expiresAt() - this.now()) / (this.ttl() * 1000))));
  protected readonly stepsList = computed(() => Array.from({ length: this.totalSteps() }, (_, i) => i + 1));
  /** Pourquoi la capture n'est pas possible, ou une chaîne vide si elle l'est. */
  protected readonly blocker = computed(() => {
    if (this.phase() !== 'running') {
      return '';
    }
    if (this.remaining() === 0) {
      return 'Temps écoulé';
    }
    if (this.faceCount() === 0) {
      return 'Aucun visage : placez votre visage dans l\'image';
    }
    if (this.faceCount() > 1) {
      return 'Un seul visage doit être dans l\'image';
    }
    const frame = this.frame()!;
    if (frame.faces[0].width < frame.imageWidth * MIN_FACE_WIDTH_RATIO) {
      return 'Votre visage est trop loin : rapprochez-vous de la caméra';
    }
    const problem = this.frame()?.reading.problem;
    if (problem) {
      return problem;
    }
    return '';
  });
  protected readonly canCapture = computed(() => this.phase() === 'running' && this.blocker() === '' && this.digit() !== null);

  private vision: Vision | null = null;
  private stream: MediaStream | null = null;
  private rafId = 0;
  private timerId: ReturnType<typeof setInterval> | null = null;
  private lastVideoTime = -1;
  private lastDetection = 0;
  private rateCount = 0;
  private rateSince = 0;
  private matchSince = 0;
  /** Faux juste après une photo, vrai dès que l'image ne montre plus le chiffre demandé. */
  private armed = true;

  protected async start(): Promise<void> {
    this.error.set('');
    if (!this.cameraOn()) {
      this.phase.set('loading');
      try {
        await this.openCamera();
        const forced = new URLSearchParams(location.search).get('delegate')?.toUpperCase();
        this.vision ??= await Vision.load(forced === 'CPU' ? 'CPU' : 'GPU');
        this.delegate.set(this.vision.delegate === 'GPU' ? 'processeur graphique' : 'processeur central');
      } catch (e) {
        this.phase.set('idle');
        this.closeCamera();
        this.error.set(cameraMessage(e));
        return;
      }
      this.loop();
    }
    await this.newChallenge();
  }

  protected async newChallenge(): Promise<void> {
    this.error.set('');
    this.verdict.set(null);
    this.doneSteps.set([]);
    this.resetHold();
    this.armed = true;
    this.phase.set('creating');
    try {
      const created = await firstValueFrom(this.api.create());
      this.challengeId.set(created.challengeId);
      this.step.set(created.step);
      this.totalSteps.set(created.totalSteps);
      this.targetDigit.set(created.targetDigit);
      this.ttl.set(created.expiresInSeconds);
      this.expiresAt.set(Date.now() + created.expiresInSeconds * 1000);
      this.startTimer();
      this.phase.set('running');
    } catch (e) {
      this.phase.set('idle');
      this.error.set(describeError(e).message);
    }
  }

  protected async capture(): Promise<void> {
    const id = this.challengeId();
    const digit = this.digit();
    if (!this.canCapture() || !id || digit === null) {
      return;
    }
    this.phase.set('sending');
    this.error.set('');
    this.resetHold();
    this.armed = false;
    const step = this.step();
    const target = this.targetDigit();
    let image: Blob;
    try {
      image = await this.grabFrame();
    } catch (e) {
      this.phase.set('running');
      this.error.set(String(e));
      return;
    }
    const photo = URL.createObjectURL(image);
    try {
      const response = await firstValueFrom(this.api.submitStep(id, step, digit, image));
      this.doneSteps.update((list) => [...list, { step, digit, photo }]);
      if (response.verdict) {
        this.finish(response.verdict);
        return;
      }
      this.step.set(response.step!);
      this.targetDigit.set(response.targetDigit!);
      this.expiresAt.set(Date.now() + response.expiresInSeconds * 1000);
      this.phase.set('running');
    } catch (e) {
      URL.revokeObjectURL(photo);
      const { message, retry } = describeError(e);
      this.error.set(message);
      if (retry) {
        this.phase.set('running');
        this.targetDigit.set(target);
      } else {
        this.stopTimer();
        this.phase.set('done');
      }
    }
  }

  private finish(verdict: Verdict): void {
    this.verdict.set(verdict);
    this.targetDigit.set(null);
    this.stopTimer();
    this.phase.set('done');
  }

  /** L'image entière de la caméra, sans recadrage ni miroir, en JPEG de bonne qualité. */
  private grabFrame(): Promise<Blob> {
    const video = this.video().nativeElement;
    const canvas = document.createElement('canvas');
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    canvas.getContext('2d')!.drawImage(video, 0, 0, canvas.width, canvas.height);
    return new Promise((resolve, reject) =>
      canvas.toBlob((blob) => (blob ? resolve(blob) : reject('La photo n\'a pas pu être prise')), 'image/jpeg', 0.92),
    );
  }

  private async openCamera(): Promise<void> {
    if (!navigator.mediaDevices?.getUserMedia) {
      throw new Error('insecure');
    }
    this.stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'user', width: { ideal: 1280 }, height: { ideal: 720 } },
      audio: false,
    });
    const video = this.video().nativeElement;
    video.srcObject = this.stream;
    await video.play();
    this.cameraOn.set(true);
  }

  private loop = (): void => {
    const video = this.video().nativeElement;
    const now = performance.now();
    // 15 analyses par seconde suffisent pour suivre des mains, et laissent respirer un téléphone modeste.
    if (this.vision && video.readyState >= 2 && video.currentTime !== this.lastVideoTime && now - this.lastDetection >= DETECTION_INTERVAL_MS) {
      this.lastVideoTime = video.currentTime;
      this.lastDetection = now;
      const frame = this.vision.detect(video, now);
      this.rateCount++;
      if (now - this.rateSince >= 1000) {
        this.rate.set(Math.round((this.rateCount * 1000) / (now - this.rateSince)));
        this.rateCount = 0;
        this.rateSince = now;
      }
      this.frame.set(frame);
      this.draw(frame, video);
      this.checkAutoCapture(now);
      if (isDevMode()) {
        // Pour les essais automatisés et le réglage du comptage : la dernière analyse, lisible depuis la console.
        (window as unknown as { __defiGestuel?: unknown }).__defiGestuel = { frame, at: Date.now(), probe: (r: Region) => this.vision?.probe(video, r) };
      }
    }
    this.rafId = requestAnimationFrame(this.loop);
  };

  /** Déclenche la photo quand le chiffre demandé est tenu assez longtemps, voir {@link HOLD_MS}. */
  private checkAutoCapture(now: number): void {
    if (this.phase() !== 'running') {
      return;
    }
    const matching = this.canCapture() && this.digit() === this.targetDigit();
    if (!matching) {
      this.armed = true;
      this.waitingNewPose.set(false);
      this.resetHold();
      return;
    }
    if (!this.autoMode()) {
      this.resetHold();
      return;
    }
    if (!this.armed) {
      this.waitingNewPose.set(true);
      return;
    }
    if (this.matchSince === 0) {
      this.matchSince = now;
    }
    const progress = Math.min(1, (now - this.matchSince) / HOLD_MS);
    this.holdProgress.set(progress);
    if (progress >= 1) {
      void this.capture();
    }
  }

  private resetHold(): void {
    this.matchSince = 0;
    this.holdProgress.set(0);
    this.waitingNewPose.set(false);
  }

  protected toggleAuto(on: boolean): void {
    this.autoMode.set(on);
    this.resetHold();
  }

  /** Le cadre des visages et le squelette des mains, sur un calque transparent posé sur la vidéo. */
  private draw(frame: Frame, video: HTMLVideoElement): void {
    const canvas = this.overlay().nativeElement;
    if (canvas.width !== video.videoWidth || canvas.height !== video.videoHeight) {
      canvas.width = video.videoWidth;
      canvas.height = video.videoHeight;
    }
    const ctx = canvas.getContext('2d')!;
    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);
    const lineWidth = Math.max(2, w / 320);

    const faceColor = frame.faces.length === 1 ? '#22c55e' : '#f59e0b';
    for (const face of frame.faces) {
      ctx.strokeStyle = faceColor;
      ctx.lineWidth = lineWidth * 1.5;
      ctx.strokeRect(face.x, face.y, face.width, face.height);
    }

    frame.hands.forEach((hand, i) => {
      const reading = frame.reading.hands[i];
      const points = hand.landmarks.map((p) => ({ x: p.x * w, y: p.y * h }));
      ctx.strokeStyle = '#38bdf8';
      ctx.lineWidth = lineWidth;
      ctx.beginPath();
      for (const [a, b] of HAND_CONNECTIONS) {
        ctx.moveTo(points[a].x, points[a].y);
        ctx.lineTo(points[b].x, points[b].y);
      }
      ctx.stroke();
      points.forEach((p, index) => {
        const tip = [4, 8, 12, 16, 20].indexOf(index);
        const up = tip >= 0 && reading?.fingers[tip];
        ctx.fillStyle = up ? '#22c55e' : tip >= 0 ? '#ef4444' : '#e0f2fe';
        ctx.beginPath();
        ctx.arc(p.x, p.y, tip >= 0 ? lineWidth * 2.2 : lineWidth * 1.3, 0, Math.PI * 2);
        ctx.fill();
      });
      if (reading) {
        // La vidéo est affichée en miroir : on retourne le texte pour qu'il se lise à l'endroit.
        const label = String(reading.count);
        const fontSize = Math.round(w / 18);
        ctx.save();
        // Sous le poignet, sans sortir de l'image.
        ctx.translate(points[0].x, Math.min(points[0].y + fontSize, h - fontSize * 0.25));
        ctx.scale(-1, 1);
        ctx.font = `700 ${fontSize}px system-ui, sans-serif`;
        ctx.textAlign = 'center';
        ctx.lineWidth = lineWidth * 2;
        ctx.strokeStyle = 'rgba(0, 0, 0, 0.7)';
        ctx.strokeText(label, 0, 0);
        ctx.fillStyle = '#ffffff';
        ctx.fillText(label, 0, 0);
        ctx.restore();
      }
    });
  }

  private startTimer(): void {
    this.stopTimer();
    this.now.set(Date.now());
    this.timerId = setInterval(() => {
      this.now.set(Date.now());
      // Délai écoulé : le serveur refusera toute étape ; on le dit tout de suite et on propose un nouveau défi.
      if (this.phase() === 'running' && this.remaining() === 0) {
        this.stopTimer();
        this.targetDigit.set(null);
        this.error.set(`Temps écoulé : les ${this.ttl()} secondes du défi sont passées. Demandez un nouveau défi.`);
        this.phase.set('done');
      }
    }, 200);
  }

  private stopTimer(): void {
    if (this.timerId !== null) {
      clearInterval(this.timerId);
      this.timerId = null;
    }
  }

  private closeCamera(): void {
    cancelAnimationFrame(this.rafId);
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    this.cameraOn.set(false);
  }

  ngOnDestroy(): void {
    this.stopTimer();
    this.closeCamera();
    this.vision?.close();
    this.doneSteps().forEach((s) => URL.revokeObjectURL(s.photo));
  }

  protected percent(similarity: number | undefined): string {
    return similarity === undefined ? '—' : `${(similarity * 100).toFixed(1).replace('.', ',')} %`;
  }

  protected doneStep(step: number): DoneStep | undefined {
    return this.doneSteps().find((s) => s.step === step);
  }
}

function cameraMessage(e: unknown): string {
  const name = (e as DOMException)?.name ?? (e as Error)?.message;
  switch (name) {
    case 'insecure':
      return 'La caméra n\'est pas disponible : il faut une connexion sécurisée (https) ou l\'adresse localhost.';
    case 'NotAllowedError':
      return 'L\'accès à la caméra a été refusé : autorisez-le dans le navigateur, puis recommencez.';
    case 'NotFoundError':
      return 'Aucune caméra n\'a été trouvée sur cet appareil.';
    case 'NotReadableError':
      return 'La caméra est déjà utilisée par une autre application.';
    default:
      return 'Impossible de démarrer : ' + String((e as Error)?.message ?? e);
  }
}
