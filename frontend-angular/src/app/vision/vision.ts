import type { FaceDetector, HandLandmarker, NormalizedLandmark } from '@mediapipe/tasks-vision';
import { FACE_MODEL, HAND_MODEL, MEDIAPIPE_WASM } from '../config';
import { Box, Region, SCAN_LEVELS, confirmRegion, confirmed, fromRegion, mergeFaces, scanRegions } from './face-search';
import { HandsReading, readDigit } from './finger-count';

/** Un visage, en pixels de l'image de la caméra. */
export type FaceBox = Box;

/** Le côté, en pixels, du dessin d'un carré de l'image donné au détecteur de visages (qui le réduit à 128). */
const REGION_PIXELS = 192;

export interface HandLandmarks {
  landmarks: NormalizedLandmark[];
  side: string | null;
}

/** Ce que MediaPipe voit sur une image de la vidéo. */
export interface Frame {
  faces: FaceBox[];
  hands: HandLandmarks[];
  reading: HandsReading;
  /** La largeur de l'image analysée, en pixels. */
  imageWidth: number;
}

/**
 * Les deux détecteurs MediaPipe, exécutés dans le navigateur : FaceDetector (les visages) et HandLandmarker (les
 * 21 repères de chaque main). La bibliothèque n'est chargée qu'au premier usage, pour ne pas alourdir l'ouverture de
 * la page. Le processeur graphique est essayé d'abord, puis le processeur central s'il n'est pas disponible.
 *
 * Les visages sont cherchés dans l'image entière et dans des morceaux agrandis, pour être vus jusqu'à deux ou trois
 * mètres de la caméra : voir face-search.ts.
 */
export class Vision {
  private readonly regionCanvas = document.createElement('canvas');
  private readonly regionContext: CanvasRenderingContext2D;
  private tracked: FaceBox[] = [];
  private scanned: Region[][] = [];
  private scanSize = '';
  private scanIndex: number[] = [];

  private constructor(
    private readonly faces: FaceDetector,
    private readonly hands: HandLandmarker,
    readonly delegate: 'GPU' | 'CPU',
  ) {
    this.regionCanvas.width = REGION_PIXELS;
    this.regionCanvas.height = REGION_PIXELS;
    this.regionContext = this.regionCanvas.getContext('2d')!;
  }

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
        // Mode image : le détecteur est appelé plusieurs fois par image de la vidéo, sur des morceaux différents.
        runningMode: 'IMAGE',
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
    const faces = this.findFaces(video);
    const handResult = this.hands.detectForVideo(video, timestamp);
    const hands: HandLandmarks[] = handResult.landmarks.map((landmarks, i) => ({
      landmarks,
      side: handResult.handedness[i]?.[0]?.categoryName ?? null,
    }));
    return { faces, hands, reading: readDigit(hands), imageWidth: video.videoWidth };
  }

  /**
   * Les visages de l'image. Candidats : l'image entière, les visages de l'analyse précédente et quelques carrés du
   * balayage ; chaque candidat ne compte que s'il est confirmé dans son carré serré. Voir face-search.ts.
   */
  private findFaces(video: HTMLVideoElement): FaceBox[] {
    const w = video.videoWidth;
    const h = video.videoHeight;
    if (this.scanSize !== `${w}x${h}`) {
      this.scanSize = `${w}x${h}`;
      this.scanned = scanRegions(w, h);
      this.scanIndex = this.scanned.map(() => 0);
    }
    const candidates = [...this.facesIn(video), ...this.tracked];
    SCAN_LEVELS.forEach(({ perFrame }, level) => {
      const regions = this.scanned[level];
      for (let i = 0; i < Math.min(perFrame, regions.length); i++) {
        candidates.push(...this.facesInRegion(video, regions[this.scanIndex[level]], w, h));
        this.scanIndex[level] = (this.scanIndex[level] + 1) % regions.length;
      }
    });
    const faces: FaceBox[] = [];
    for (const candidate of mergeFaces(candidates)) {
      const face = confirmed(candidate, this.facesInRegion(video, confirmRegion(candidate, w, h), w, h));
      if (face) {
        faces.push(face);
      }
    }
    this.tracked = mergeFaces(faces);
    return this.tracked;
  }

  /** Pour le réglage, en développement : les visages trouvés dans un carré donné de l'image. */
  probe(video: HTMLVideoElement, region: Region): FaceBox[] {
    this.regionContext.drawImage(video, region.x, region.y, region.size, region.size, 0, 0, REGION_PIXELS, REGION_PIXELS);
    return this.facesIn(this.regionCanvas).map((b) => ({ ...b, x: region.x + (b.x * region.size) / REGION_PIXELS, y: region.y + (b.y * region.size) / REGION_PIXELS, width: (b.width * region.size) / REGION_PIXELS, height: (b.height * region.size) / REGION_PIXELS }));
  }

  private facesInRegion(video: HTMLVideoElement, region: Region, w: number, h: number): FaceBox[] {
    this.regionContext.drawImage(video, region.x, region.y, region.size, region.size, 0, 0, REGION_PIXELS, REGION_PIXELS);
    return fromRegion(this.facesIn(this.regionCanvas), region, REGION_PIXELS, w, h);
  }

  private facesIn(image: HTMLVideoElement | HTMLCanvasElement): FaceBox[] {
    return this.faces
      .detect(image)
      .detections.filter((d) => d.boundingBox)
      .map((d) => ({
        x: d.boundingBox!.originX,
        y: d.boundingBox!.originY,
        width: d.boundingBox!.width,
        height: d.boundingBox!.height,
        score: d.categories[0]?.score ?? 0,
      }));
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
