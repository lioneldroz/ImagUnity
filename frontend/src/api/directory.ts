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
}

/** GET /api/v1/directory. */
export interface DirectoryView {
  enabled: boolean
  boxId: string | null
  shortCode: string | null
  lastSync: { at: string | null; accepted: number; rejected: number; error: string | null } | null
  boxes: DirectoryBox[]
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
