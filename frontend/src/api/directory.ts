import { getJson, postBody } from './http'

export type BoxStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED' | 'NOT_YET_VALID'

export interface DirectoryBox {
  boxId: string
  displayName: string
  serial: number
  status: BoxStatus
  validUntil: string
  /** La box publie une adresse de contact. */
  reachable: boolean
  /** Nom local du partenaire lié à cette box (null : pas appairée). */
  pairedAs: string | null
  /** L’entrée publie les clés de chiffrement de la box (sinon : entrée à réémettre). */
  encrypted: boolean
  /** Organisation partenaire de la box (null : box de l’organisation). */
  organization: string | null
  /** Motif pour lequel cette box ne peut pas échanger avec elle (accord, périmètre), ou null. */
  refusal: string | null
}

export interface SyncState {
  at: string | null
  accepted: number
  rejected: number
  error: string | null
}

/** Organisation partenaire reconnue par le responsable (fédération). */
export interface PartnerOrganization {
  organization: string
  shortCode: string
  status: BoxStatus
  validUntil: string
  localGroups: string[]
  partnerGroups: string[]
  relays: string[]
  lastSync: SyncState | null
  /** Box utilisables de ce partenaire. */
  boxes: number
  freshness: Freshness | null
}

/** Fraîcheur d'un annuaire : date de la dernière preuve, annuaire figé, motif d'une lecture écartée. */
export interface Freshness {
  issuedAt: string | null
  stale: boolean
  error: string | null
}

/** Identifiant de box revendiqué par deux organisations. */
export interface IdentifierCollision {
  boxId: string
  claimant: string
  holder: string
  at: string
}

/** GET /api/v1/directory. */
export interface DirectoryView {
  enabled: boolean
  boxId: string | null
  shortCode: string | null
  lastSync: SyncState | null
  boxes: DirectoryBox[]
  partners: PartnerOrganization[]
  collisions: IdentifierCollision[]
  freshness: Freshness | null
}

export const fetchDirectory = () => getJson<DirectoryView>('/api/v1/directory')

export const pairWithBox = (boxId: string, name: string) =>
  postBody(`/api/v1/directory/boxes/${boxId}/pair`, { name })

export const ENROLLMENT_REQUEST_URL = '/api/v1/directory/enrollment-request'

export const BOX_STATUS_LABELS: Record<BoxStatus, string> = {
  ACTIVE: 'Active',
  REVOKED: 'Révoquée',
  EXPIRED: 'Expirée',
  NOT_YET_VALID: 'Pas encore valide',
}
