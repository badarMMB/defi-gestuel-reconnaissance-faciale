/**
 * Compter les doigts levés à partir des 21 repères d'une main donnés par MediaPipe HandLandmarker.
 *
 * Repères (https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker) :
 * 0 poignet ; pouce 1-4 ; index 5-8 ; majeur 9-12 ; annulaire 13-16 ; auriculaire 17-20
 * (pour chaque doigt : base MCP, articulation PIP, articulation DIP, bout).
 *
 * Les tests se font dans le repère de la main elle-même (distances au poignet, axe auriculaire → index), pas dans celui
 * de l'image. Ils valent donc pour une main droite ou gauche, la paume ou le dos tournés vers la caméra, et la main
 * inclinée. La latéralité (« Left » / « Right ») donnée par MediaPipe sert à autre chose : exiger une main gauche et
 * une main droite, donc deux mains distinctes, et non deux détections de la même main.
 */

export interface Point {
  x: number;
  y: number;
  z?: number;
}

export type Side = 'Left' | 'Right';

export interface HandReading {
  side: Side | null;
  /** Les doigts levés, dans l'ordre pouce, index, majeur, annulaire, auriculaire. */
  fingers: [boolean, boolean, boolean, boolean, boolean];
  count: number;
}

const WRIST = 0;
const THUMB = { mcp: 2, ip: 3, tip: 4 };
const FINGERS = [
  { mcp: 5, pip: 6, dip: 7, tip: 8 },
  { mcp: 9, pip: 10, dip: 11, tip: 12 },
  { mcp: 13, pip: 14, dip: 15, tip: 16 },
  { mcp: 17, pip: 18, dip: 19, tip: 20 },
];
const INDEX_MCP = 5;
const MIDDLE_MCP = 9;
const PINKY_MCP = 17;

/** Un doigt est levé si son bout est nettement plus loin du poignet que son articulation PIP, et le doigt presque droit. */
const FINGER_EXTENSION_RATIO = 1.12;
const FINGER_MIN_ANGLE = 140;
/**
 * Le pouce est levé s'il dépasse nettement le côté de l'index, s'écarte de la paume, et est presque droit.
 * Mesures sur des photos réelles : pouce plié ou contre la paume, dépassement ≤ 0,97 et écart ≤ 0,32 ;
 * pouce sorti, dépassement ≥ 1,96 et écart ≥ 0,40. Les seuils sont pris entre les deux.
 */
const THUMB_LATERAL_MIN = 1.2;
const THUMB_SPREAD_MIN = 0.3;
const THUMB_MIN_ANGLE = 140;

function distance(a: Point, b: Point): number {
  return Math.hypot(a.x - b.x, a.y - b.y, (a.z ?? 0) - (b.z ?? 0));
}

/** L'angle en degrés au point b, entre les segments b→a et b→c (180 : alignés). */
function angle(a: Point, b: Point, c: Point): number {
  const u = { x: a.x - b.x, y: a.y - b.y, z: (a.z ?? 0) - (b.z ?? 0) };
  const v = { x: c.x - b.x, y: c.y - b.y, z: (c.z ?? 0) - (b.z ?? 0) };
  const dot = u.x * v.x + u.y * v.y + u.z * v.z;
  const norm = Math.hypot(u.x, u.y, u.z) * Math.hypot(v.x, v.y, v.z);
  if (norm === 0) {
    return 0;
  }
  return (Math.acos(Math.max(-1, Math.min(1, dot / norm))) * 180) / Math.PI;
}

/** La position de p le long de l'axe a→b : 0 en a, 1 en b, plus de 1 au-delà de b. */
function along(p: Point, a: Point, b: Point): number {
  const ab = { x: b.x - a.x, y: b.y - a.y };
  const length2 = ab.x * ab.x + ab.y * ab.y;
  if (length2 === 0) {
    return 0;
  }
  return ((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / length2;
}

export function fingerExtended(landmarks: readonly Point[], finger: 0 | 1 | 2 | 3): boolean {
  const f = FINGERS[finger];
  const wrist = landmarks[WRIST];
  const longer = distance(wrist, landmarks[f.tip]) > distance(wrist, landmarks[f.pip]) * FINGER_EXTENSION_RATIO;
  const straight = angle(landmarks[f.mcp], landmarks[f.pip], landmarks[f.tip]) > FINGER_MIN_ANGLE;
  return longer && straight;
}

export function thumbExtended(landmarks: readonly Point[]): boolean {
  const handSize = distance(landmarks[WRIST], landmarks[MIDDLE_MCP]);
  if (handSize === 0) {
    return false;
  }
  const tip = landmarks[THUMB.tip];
  // Au-delà de la base de l'index, en partant de l'auriculaire : le pouce sort de la main.
  const lateral = along(tip, landmarks[PINKY_MCP], landmarks[INDEX_MCP]);
  const spread = distance(tip, landmarks[INDEX_MCP]) / handSize;
  const straight = angle(landmarks[THUMB.mcp], landmarks[THUMB.ip], tip) > THUMB_MIN_ANGLE;
  return lateral > THUMB_LATERAL_MIN && spread > THUMB_SPREAD_MIN && straight;
}

export function readHand(landmarks: readonly Point[], side: string | null | undefined): HandReading {
  if (landmarks.length < 21) {
    throw new Error('une main a 21 repères, reçu ' + landmarks.length);
  }
  const fingers: HandReading['fingers'] = [
    thumbExtended(landmarks),
    fingerExtended(landmarks, 0),
    fingerExtended(landmarks, 1),
    fingerExtended(landmarks, 2),
    fingerExtended(landmarks, 3),
  ];
  return {
    side: side === 'Left' || side === 'Right' ? side : null,
    fingers,
    count: fingers.filter(Boolean).length,
  };
}

export interface HandsReading {
  hands: HandReading[];
  /** Le total des doigts levés sur les deux mains, ou null si la lecture n'est pas valable pour un chiffre. */
  digit: number | null;
  /** Pourquoi la lecture n'est pas valable, à afficher. */
  problem: string | null;
}

/**
 * Le chiffre montré avec deux mains : de 0 (deux poings) à 9. Dix doigts levés ne font pas un chiffre. Il faut
 * exactement deux mains, une gauche et une droite.
 */
export function readDigit(hands: readonly { landmarks: readonly Point[]; side: string | null | undefined }[]): HandsReading {
  const readings = hands.map((h) => readHand(h.landmarks, h.side));
  if (readings.length !== 2) {
    return { hands: readings, digit: null, problem: readings.length < 2 ? 'Montrez vos deux mains' : 'Trop de mains dans l\'image' };
  }
  const sides = readings.map((r) => r.side);
  if (sides[0] !== null && sides[0] === sides[1]) {
    return { hands: readings, digit: null, problem: 'Il faut une main gauche et une main droite' };
  }
  const total = readings[0].count + readings[1].count;
  if (total > 9) {
    return { hands: readings, digit: null, problem: '10 doigts : ce n\'est pas un chiffre' };
  }
  return { hands: readings, digit: total, problem: null };
}
