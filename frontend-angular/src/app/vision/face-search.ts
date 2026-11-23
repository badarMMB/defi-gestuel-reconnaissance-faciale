/**
 * Trouver un visage loin de la caméra.
 *
 * Le détecteur de visages de MediaPipe (BlazeFace « courte portée ») réduit l'image qu'on lui donne à 128 × 128 pixels
 * et ne voit un visage que s'il occupe au moins un septième environ de cette image. Sur une image de 1280 × 720, un
 * visage de moins de 170 pixels de large n'est plus vu : au-delà d'un mètre de la caméra. On procède donc en deux temps.
 *
 * 1. Trouver des candidats : l'image entière (les visages proches), les visages de l'analyse précédente, et un balayage
 *    par carrés de la hauteur, de la moitié et du tiers de la hauteur de l'image, quelques carrés de chaque taille par
 *    analyse, à tour de rôle. Les carrés d'une même taille se chevauchent assez pour qu'un visage de la taille qu'ils
 *    savent voir tienne entier dans l'un d'eux.
 * 2. Confirmer chaque candidat dans un carré serré, quatre fois sa taille, centré sur lui, avec un score d'au moins
 *    {@link CONFIRM_SCORE}. Agrandir un morceau d'image fait parfois prendre une main pour un visage ; dans le carré
 *    serré, ce faux visage disparaît, alors qu'un vrai visage y est vu avec plus de certitude encore. Ce carré sert aussi
 *    à suivre le visage d'une analyse à l'autre.
 *
 * Ce fichier ne contient que la géométrie, sans MediaPipe, pour être testé seul.
 */

export interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
  score: number;
}

/** Un morceau carré de l'image, en pixels de l'image. */
export interface Region {
  x: number;
  y: number;
  size: number;
}

/** Le score qu'un visage doit avoir dans son carré de confirmation pour compter. */
export const CONFIRM_SCORE = 0.8;

/**
 * Le carré de confirmation fait quatre fois la taille du visage : le détecteur est le plus sûr de lui quand le visage
 * occupe entre un sixième et un quart du carré (mesuré : score 0,92 à 0,95 ; à un tiers, il tombe à 0,77).
 */
const CONFIRM_FACTOR = 4;

/** Les tailles de carrés du balayage, en fraction de la hauteur de l'image, et combien d'entre eux fouiller par analyse. */
export const SCAN_LEVELS: ReadonlyArray<{ fraction: number; perFrame: number }> = [
  { fraction: 1, perFrame: 1 },
  { fraction: 1 / 2, perFrame: 2 },
  { fraction: 1 / 3, perFrame: 2 },
];

/** Les carrés à balayer, un tableau par taille, sur une image de largeur w et de hauteur h. */
export function scanRegions(w: number, h: number): Region[][] {
  return SCAN_LEVELS.map(({ fraction }) => {
    const size = Math.round(h * fraction);
    // Chevauchement de 35 % : un visage de la taille que ce carré sait voir tient entier dans l'un des carrés.
    const step = size * 0.65;
    const level: Region[] = [];
    for (const y of spread(h - size, step)) {
      for (const x of spread(w - size, step)) {
        level.push({ x, y, size });
      }
    }
    return level;
  });
}

/** Des positions de 0 à max, régulièrement espacées d'au plus step. */
function spread(max: number, step: number): number[] {
  if (max <= 0) {
    return [0];
  }
  const n = Math.ceil(max / step);
  return Array.from({ length: n + 1 }, (_, i) => Math.round((max * i) / n));
}

/** Le carré de confirmation d'un visage : quatre fois sa taille, centré sur lui, sans sortir de l'image. */
export function confirmRegion(face: Box, w: number, h: number): Region {
  const size = Math.round(Math.min(h, w, Math.max(Math.max(face.width, face.height) * CONFIRM_FACTOR, 48)));
  const cx = face.x + face.width / 2;
  const cy = face.y + face.height / 2;
  return {
    x: Math.round(clamp(cx - size / 2, 0, w - size)),
    y: Math.round(clamp(cy - size / 2, 0, h - size)),
    size,
  };
}

/**
 * Le visage confirmé parmi les détections faites dans le carré de confirmation d'un candidat : la plus sûre de celles
 * qui recouvrent le candidat, si son score atteint {@link CONFIRM_SCORE}.
 */
export function confirmed(candidate: Box, detections: readonly Box[]): Box | null {
  const best = detections
    .filter((d) => d.score >= CONFIRM_SCORE && sameFace(d, candidate))
    .sort((a, b) => b.score - a.score)[0];
  return best ?? null;
}

/**
 * Ramène dans l'image les visages trouvés dans un carré (coordonnées en pixels du dessin du carré, de côté drawn). Un
 * visage qui touche un bord du carré à l'intérieur de l'image est écarté : c'est sans doute un visage coupé, que
 * d'autres carrés voient entier.
 */
export function fromRegion(boxes: readonly Box[], region: Region, drawn: number, w: number, h: number): Box[] {
  const k = region.size / drawn;
  const margin = region.size * 0.01;
  return boxes
    .map((b) => ({ x: region.x + b.x * k, y: region.y + b.y * k, width: b.width * k, height: b.height * k, score: b.score }))
    .filter((b) => {
      const cutLeft = region.x > 0 && b.x <= region.x + margin;
      const cutTop = region.y > 0 && b.y <= region.y + margin;
      const cutRight = region.x + region.size < w && b.x + b.width >= region.x + region.size - margin;
      const cutBottom = region.y + region.size < h && b.y + b.height >= region.y + region.size - margin;
      return !(cutLeft || cutTop || cutRight || cutBottom);
    });
}

/** Un même visage vu plusieurs fois ne compte qu'une fois : on garde la détection la plus sûre. */
export function mergeFaces(boxes: readonly Box[]): Box[] {
  const kept: Box[] = [];
  for (const b of [...boxes].sort((a, c) => c.score - a.score)) {
    if (!kept.some((k) => sameFace(k, b))) {
      kept.push(b);
    }
  }
  return kept;
}

/** Deux cadres désignent le même visage si le plus petit est pour plus de moitié dans l'autre. */
function sameFace(a: Box, b: Box): boolean {
  const ix = Math.max(0, Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x));
  const iy = Math.max(0, Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y));
  const inter = ix * iy;
  const smaller = Math.min(a.width * a.height, b.width * b.height);
  return smaller > 0 && inter / smaller > 0.5;
}

function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v));
}
