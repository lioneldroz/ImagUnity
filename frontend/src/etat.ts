import type { CentralConfigurationView, DicomDevices, DicomNode } from './api/configuration'
import type { DirectoryView } from './api/directory'
import type { InboundTransfer, OutboundTransfer, TransferPart } from './api/transfers'
import type { Etape, Tone } from './components/ui'
import { ilYa, quand, shortUid } from './format'

// --- Envois -----------------------------------------------------------------------------------------------------

export const OUT_STEPS = ['Reçu du PACS', 'Empaqueté', 'Déposé', 'Annoncé', 'Confirmé']
const OUT_INDEX = { RECEIVED: 0, PACKAGED: 1, UPLOADED: 2, ANNOUNCED: 3, ACKNOWLEDGED: 4 } as const

export function outTone(t: OutboundTransfer): Tone {
  if (t.state === 'FAILED') return 'fail'
  if (t.state === 'ACKNOWLEDGED') return 'ok'
  return 'progress'
}

/** Étape où un envoi a échoué, d'après ses paquets : pas de paquet, paquets pas tous déposés, ou annonce. */
export function failedStep(t: OutboundTransfer, parts: TransferPart[] | undefined): number {
  if (t.partCount === 0 && (!parts || parts.length === 0)) return 1
  if (!parts) return 2
  return parts.every((p) => p.uploaded) ? 3 : 2
}

/** Parcours d'un envoi : heure de la première étape (création) et de l'étape atteinte (dernière mise à jour). */
export function outSteps(t: OutboundTransfer, parts: TransferPart[] | undefined, now: number): Etape[] {
  const failedAt = t.state === 'FAILED' ? failedStep(t, parts) : -1
  const reached = t.state === 'FAILED' ? failedAt : OUT_INDEX[t.state] + 1
  return OUT_STEPS.map((label, i) => {
    const state = i === failedAt ? 'fail' : i < reached ? 'ok' : i === reached ? 'progress' : 'todo'
    let at: string | null = null
    if (i === 0) at = quand(t.createdAt, now)
    else if (state === 'fail') at = `échec ${quand(t.updatedAt, now)}`
    else if (i === reached - 1) at = quand(t.updatedAt, now)
    return { label, state, at }
  })
}

// --- Réceptions -------------------------------------------------------------------------------------------------

export const IN_STEPS = ['Annoncée', 'Paquets restitués', 'Restituée', 'Accusé envoyé']

export function inTone(t: InboundTransfer): Tone {
  if (t.state === 'FAILED') return 'fail'
  if (t.state === 'RESTORED') return t.ackSent ? 'ok' : 'progress'
  return 'progress'
}

export function inLabel(t: InboundTransfer): string {
  if (t.state === 'FAILED') return 'Restitution en échec'
  if (t.state === 'RESTORED') return t.ackSent ? 'Restituée' : 'Restituée, accusé à envoyer'
  return t.restoredParts > 0 ? 'Restitution en cours' : 'En réception'
}

export function inSteps(t: InboundTransfer, now: number): Etape[] {
  const failedAt = t.state === 'FAILED' ? (t.restoredParts < t.partCount ? 1 : 2) : -1
  const reached = t.state === 'FAILED' ? failedAt : t.state === 'ANNOUNCED' ? 1 : t.ackSent ? 4 : 3
  return IN_STEPS.map((label, i) => {
    const state = i === failedAt ? 'fail' : i < reached ? 'ok' : i === reached ? 'progress' : 'todo'
    let at: string | null = null
    if (i === 0) at = quand(t.createdAt, now)
    else if (state === 'fail') at = `échec ${quand(t.updatedAt, now)}`
    else if (i === reached - 1) at = quand(t.updatedAt, now)
    else if (i === 1 && state === 'progress') at = `${t.restoredParts} / ${t.partCount}`
    return { label, state, at }
  })
}

// --- Appareils --------------------------------------------------------------------------------------------------

export interface EtatAppareil {
  label: string
  tone: Tone
  detail: string
}

const majuscule = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)

/** État d'un appareil d'après ce que la box en constate : essai de connexion (destination), envois reçus (source). */
export function etatAppareil(n: DicomNode, boxAet: string | null, now: number): EtatAppareil[] {
  const c = n.check
  const out: EtatAppareil[] = []
  if (n.destination) {
    if (!c?.echoAt) {
      out.push({ label: 'Pas encore essayé', tone: 'neutral', detail: '' })
    } else if (c.echoError) {
      out.push({
        label: `C-ECHO en échec ${quand(c.echoAt, now)}`,
        tone: 'fail',
        detail:
          `${majuscule(c.echoError)}. Vérifiez ${n.host}${n.port ? `:${n.port}` : ''}, l’AET ${n.aet}, et que le PACS autorise la box ` +
          `(${boxAet ?? 'son AET local'}). ` +
          (c.echoOkAt ? `Dernier succès : ${quand(c.echoOkAt, now)}.` : 'Aucun succès depuis que cette adresse est déclarée.'),
      })
    } else {
      out.push({ label: `C-ECHO réussi ${quand(c.echoAt, now)}`, tone: 'ok', detail: '' })
    }
  }
  if (n.source) {
    if (c?.refusedAt && (!c.receivedAt || c.refusedAt > c.receivedAt)) {
      out.push({
        label: `Connexion refusée ${quand(c.refusedAt, now)}`,
        tone: 'warn',
        detail: `Venue de ${c.refusedFrom ?? 'une adresse inconnue'}${n.host ? `, alors que l’adresse déclarée est ${n.host}` : ''}.`,
      })
    } else if (c?.receivedAt) {
      out.push({ label: `Envoi reçu ${quand(c.receivedAt, now)}`, tone: 'ok', detail: '' })
    } else if (!n.destination) {
      out.push({ label: 'Aucun envoi reçu', tone: 'neutral', detail: '' })
    }
  }
  return out
}

export const appareilsEnEchec = (d: DicomDevices | null | undefined) =>
  (d?.nodes ?? []).filter((n) => n.destination && n.check?.echoAt && n.check.echoError)

/** Dernier essai de connexion et prochain essai prévu (le plus ancien essai + la période). */
export function essais(d: DicomDevices): { last: string | null; next: string | null } {
  const at = d.nodes.filter((n) => n.destination && n.check?.echoAt).map((n) => n.check!.echoAt!).sort()
  if (at.length === 0) return { last: null, next: null }
  const next = new Date(new Date(at[0]).getTime() + d.echoEveryMinutes * 60_000).toISOString()
  return { last: at[at.length - 1], next }
}

// --- Points à surveiller ---------------------------------------------------------------------------------------

export interface PointASurveiller {
  key: string
  tone: Tone
  title: string
  detail: string
  action: string
  href: string
}

/** Points à surveiller : envois et réceptions en échec, accusés en retard, appareils injoignables, configuration refusée. */
export function aSurveiller(
  out: OutboundTransfer[] | undefined,
  inb: InboundTransfer[] | undefined,
  conf: CentralConfigurationView | undefined,
  dir: DirectoryView | undefined,
  now: number,
): PointASurveiller[] {
  const items: PointASurveiller[] = []
  for (const t of out ?? []) {
    if (t.state === 'FAILED') {
      items.push({
        key: `out-${t.id}`, tone: 'fail', title: `Envoi en échec vers ${t.destination}`,
        detail: `Étude ${shortUid(t.studyInstanceUid)}${t.errorMessage ? ` : ${t.errorMessage}` : ''}`,
        action: 'Voir l’envoi', href: '#envois',
      })
    } else if (t.ackOverdue) {
      items.push({
        key: `ack-${t.id}`, tone: 'warn', title: `Accusé en retard : ${t.destination}`,
        detail: `Étude ${shortUid(t.studyInstanceUid)}, annoncée ${ilYa(t.updatedAt, now)}, toujours sans accusé de réception.`,
        action: 'Voir l’envoi', href: '#envois',
      })
    }
  }
  for (const t of inb ?? []) {
    if (t.state === 'FAILED') {
      items.push({
        key: `in-${t.peerName}-${t.transferId}`, tone: 'fail', title: `Réception en échec de ${t.peerName}`,
        detail: `Étude ${shortUid(t.studyInstanceUid)}${t.errorMessage ? ` : ${t.errorMessage}` : ''}`,
        action: 'Voir la réception', href: '#receptions',
      })
    }
  }
  for (const n of appareilsEnEchec(conf?.devices)) {
    items.push({
      key: `node-${n.name}`, tone: 'fail', title: `${n.name} ne répond pas`,
      detail: `C-ECHO en échec ${ilYa(n.check!.echoAt!, now)} : ${n.check!.echoError}.`,
      action: 'Voir l’appareil', href: '#configuration',
    })
  }
  if (conf && conf.refused > 0 && conf.refusal) {
    items.push({
      key: 'refus', tone: 'warn', title: `Configuration n° ${conf.refused} refusée`,
      detail: `${conf.refusal}. La configuration en vigueur reste appliquée.`, action: 'Voir la configuration', href: '#configuration',
    })
  }
  if (dir && dir.collisions.length > 0) {
    const c = dir.collisions[dir.collisions.length - 1]
    items.push({
      key: 'collision', tone: 'warn', title: 'Identifiant de box revendiqué par deux organisations',
      detail: `Box ${shortUid(c.boxId)}, revendiquée par ${c.claimant}, tenue par ${c.holder} : entrée écartée. Prévenir le responsable.`,
      action: 'Voir l’annuaire', href: '#partenaires',
    })
  }
  return items
}

