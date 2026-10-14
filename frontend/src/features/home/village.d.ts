export type VillagePhase = 'day' | 'night'

export interface VillageActor {
  id: number
  name: string
  x: number
  y: number
  color: string
  step: number
  wolf: boolean
  raising: boolean
  opacity?: number
  throughDoor?: boolean
  interactive?: boolean
}

export interface SceneBounds {
  width: number
  height: number
  crop?: number
  anchorY?: number
  hideActors?: boolean
  dusk?: boolean
}

export interface ProjectedVillageActor extends VillageActor {
  screenX: number
  screenY: number
}

export interface VillageRenderer {
  drawIllustration(variant?: 'red' | 'grey' | 'trees'): void
  drawPortrait(color?: string): void
  drawGameTable(phase?: VillagePhase): void
  draw(
    time: number,
    phase: VillagePhase,
    phaseTime: number,
    reducedMotion?: boolean,
    interaction?: { id: number; text: string } | null,
    viewport?: Partial<SceneBounds>,
  ): VillageActor[]
}

export function createVillage(canvas: HTMLCanvasElement): VillageRenderer
export function getVillageActors(phase: VillagePhase, phaseTime: number, time?: number, reducedMotion?: boolean): VillageActor[]
export function projectVillageActors(actors: VillageActor[], viewport: SceneBounds): ProjectedVillageActor[]
export function getNightVisit(phaseTime: number): {
  doorOpen: number
  windowLight: number
  house: { x: number; y: number; width: number; height: number }
}
