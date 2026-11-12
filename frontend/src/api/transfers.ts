import { getJson, postJson } from './http'

export type OutboundState = 'RECEIVED' | 'PACKAGED' | 'UPLOADED' | 'ANNOUNCED' | 'ACKNOWLEDGED' | 'FAILED'
export type InboundState = 'ANNOUNCED' | 'RESTORED' | 'FAILED'

/** Transfert envoyé (GET /api/v1/transfers). */
export interface OutboundTransfer {
  id: string
  studyInstanceUid: string
  calledAet: string
  destination: string
  state: OutboundState
  sizeBytes: number
  partCount: number
  errorMessage: string | null
  createdAt: string
  updatedAt: string
  ackOverdue: boolean
}

export interface TransferPart {
  index: number
  count: number
  fileName: string
  size: number
  sha256: string
  handedOver: boolean
  uploaded: boolean
}

/** GET /api/v1/transfers/{id}. */
export interface OutboundDetail {
  transfer: OutboundTransfer
  parts: TransferPart[]
}

/** Transfert reçu (GET /api/v1/inbound-transfers). */
export interface InboundTransfer {
  transferId: string
  peerName: string
  studyInstanceUid: string
  partCount: number
  state: InboundState
  errorMessage: string | null
  restoredInstances: number
  ackSent: boolean
  createdAt: string
  updatedAt: string
}

/** GET /api/v1/overview. */
export interface Overview {
  outbound: Partial<Record<OutboundState, number>>
  inbound: Partial<Record<InboundState, number>>
  ackOverdue: number
  peers: number
}

export interface Peer {
  name: string
  displayName: string | null
  pairedAt: string
  /** Box de l’annuaire à laquelle le partenaire est lié (null : pas encore vérifié). */
  boxId: string | null
  directoryStatus: 'ACTIVE' | 'REVOKED' | 'EXPIRED' | 'NOT_YET_VALID' | null
}

export const fetchOverview = () => getJson<Overview>('/api/v1/overview')
export const fetchTransfers = () => getJson<OutboundTransfer[]>('/api/v1/transfers?limit=100')
export const fetchTransfer = (id: string) => getJson<OutboundDetail>(`/api/v1/transfers/${id}`)
export const fetchInboundTransfers = () => getJson<InboundTransfer[]>('/api/v1/inbound-transfers?limit=100')
export const fetchPeers = () => getJson<Peer[]>('/api/v1/peers')
export const retryTransfer = (id: string) => postJson<OutboundTransfer>(`/api/v1/transfers/${id}/retry`)

export const OUTBOUND_LABELS: Record<OutboundState, string> = {
  RECEIVED: 'Reçu du PACS',
  PACKAGED: 'Empaqueté',
  UPLOADED: 'Déposé',
  ANNOUNCED: 'Annoncé',
  ACKNOWLEDGED: 'Confirmé',
  FAILED: 'En échec',
}

export const INBOUND_LABELS: Record<InboundState, string> = {
  ANNOUNCED: 'En réception',
  RESTORED: 'Restitué',
  FAILED: 'En échec',
}
