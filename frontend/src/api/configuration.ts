import { getJson } from './http'

/** GET /api/v1/configuration : configuration centralisée appliquée par la box. */
export interface CentralConfigurationView {
  /** Faux : la box garde sa configuration locale (annuaire désactivé ou verrouillage local). */
  enabled: boolean
  /** Version appliquée, 0 : configuration locale. */
  sequence: number
  issuedAt: string | null
  author: string | null
  comment: string | null
  appliedAt: string | null
  /** La version fixe les règles de priorité / de routage (sinon : règles locales). */
  priority: boolean
  routing: boolean
  /** Règles de routage centrales : « nom → partenaires ». */
  routingRules: string[]
  /** Dernière version refusée, 0 : aucune. */
  refused: number
  refusal: string | null
  checkedAt: string | null
  error: string | null
  /** Groupes de la box, d'après son entrée d'annuaire (fixés par le responsable). */
  groups: string[]
  /** Nœuds et routes DICOM centraux, une ligne chacun (vide : configuration DICOM locale). */
  dicom?: string[]
  /** Les mêmes, structurés (null : configuration DICOM locale). */
  devices?: DicomDevices | null
}

/** Ce que la box constate d'un appareil : essais de connexion (destination), envois reçus (source). */
export interface NodeCheck {
  node: string
  echoAt?: string | null
  echoError?: string | null
  echoOkAt?: string | null
  receivedAt?: string | null
  refusedAt?: string | null
  refusedFrom?: string | null
}

export interface DicomNode {
  name: string
  aet: string
  host: string | null
  port: number | null
  source: boolean
  destination: boolean
  check: NodeCheck | null
}

/** Envoi (from : appareils, to : identifiants de box) ou réception (from : identifiants de box, to : PACS). */
export interface DicomRoute {
  name: string
  from: string[]
  calledAet: string | null
  filter: string | null
  to: string[]
}

export interface DicomDevices {
  aet: string | null
  nodes: DicomNode[]
  outgoing: DicomRoute[]
  incoming: DicomRoute[]
  echoEveryMinutes: number
}

export const fetchCentralConfiguration = () => getJson<CentralConfigurationView>('/api/v1/configuration')
