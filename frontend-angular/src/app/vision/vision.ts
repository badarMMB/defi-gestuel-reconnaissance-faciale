import type { FaceDetector, HandLandmarker, NormalizedLandmark } from '@mediapipe/tasks-vision';
import { FACE_MODEL, HAND_MODEL, MEDIAPIPE_WASM } from '../config';
import { HandsReading, readDigit } from './finger-count';

export interface FaceBox {
  x: number;
  y: number;
  width: number;
  height: number;
  score: number;
}

export interface HandLandmarks {
  landmarks: NormalizedLandmark[];
  side: string | null;
}

/** Ce que MediaPipe voit sur une image de la vidéo. */
export interface Frame {
  faces: FaceBox[];
  hands: HandLandmarks[];
  reading: HandsReading;
}

/**
 * Les deux détecteurs MediaPipe, exécutés dans le navigateur : FaceDetector (les visages) et HandLandmarker (les
 * 21 repères de chaque main). La bibliothèque n'est chargée qu'au premier usage, pour ne pas alourdir l'ouverture de
 * la page. Le processeur graphique est essayé d'abord, puis le processeur central s'il n'est pas disponible.
 */
export class Vision {
  private constructor(
    private readonly faces: FaceDetector,
    private readonly hands: HandLandmarker,
    readonly delegate: 'GPU' | 'CPU',
  ) {}

  /**
   * @param preferred le processeur à utiliser : « GPU » (par défaut, avec repli sur le processeur central) ou « CPU »
   * (forcé, utile si le processeur graphique de l'appareil est absent ou défaillant)
   */
  static async load(preferred: 'GPU' | 'CPU' = 'GPU'): Promise<Vision> {
    const mp = await import('@mediapipe/tasks-vision');
    const fileset = await mp.FilesetResolver.forVisionTasks(MEDIAPIPE_WASM);
    const create = async (delegate: 'GPU' | 'CPU') => {
      const faces = await mp.FaceDetector.createFromOptions(fileset, {
        baseOptions: { modelAssetPath: FACE_MODEL, delegate },
        runningMode: 'VIDEO',
        minDetectionConfidence: 0.6,
      });
      const hands = await mp.HandLandmarker.createFromOptions(fileset, {
        baseOptions: { modelAssetPath: HAND_MODEL, delegate },
        runningMode: 'VIDEO',
        // Jusqu'à 4 mains : on veut savoir s'il y en a trop, pas seulement en trouver deux.
        numHands: 4,
        minHandDetectionConfidence: 0.6,
        minHandPresenceConfidence: 0.6,
        minTrackingConfidence: 0.5,
      });
      return new Vision(faces, hands, delegate);
    };
    if (preferred === 'CPU') {
      return create('CPU');
    }
    try {
      return await create('GPU');
    } catch (gpuError) {
      console.warn('MediaPipe sur le processeur graphique impossible, passage au processeur central', gpuError);
      return create('CPU');
    }
  }

  detect(video: HTMLVideoElement, timestamp: number): Frame {
    const faceResult = this.faces.detectForVideo(video, timestamp);
    const handResult = this.hands.detectForVideo(video, timestamp);
    const faces: FaceBox[] = faceResult.detections
      .filter((d) => d.boundingBox)
      .map((d) => ({
        x: d.boundingBox!.originX,
        y: d.boundingBox!.originY,
        width: d.boundingBox!.width,
        height: d.boundingBox!.height,
        score: d.categories[0]?.score ?? 0,
      }));
    const hands: HandLandmarks[] = handResult.landmarks.map((landmarks, i) => ({
      landmarks,
      side: handResult.handedness[i]?.[0]?.categoryName ?? null,
    }));
    return { faces, hands, reading: readDigit(hands) };
  }

  close(): void {
    this.faces.close();
    this.hands.close();
  }
}

/** Les liaisons entre repères d'une main, pour la dessiner (mêmes paires que HandLandmarker.HAND_CONNECTIONS). */
export const HAND_CONNECTIONS: ReadonlyArray<[number, number]> = [
  [0, 1], [1, 2], [2, 3], [3, 4],
  [0, 5], [5, 6], [6, 7], [7, 8],
  [5, 9], [9, 10], [10, 11], [11, 12],
  [9, 13], [13, 14], [14, 15], [15, 16],
  [13, 17], [0, 17], [17, 18], [18, 19], [19, 20],
];
