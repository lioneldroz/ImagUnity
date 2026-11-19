import { useQuery } from '@tanstack/react-query'
import { fetchBoxInfo } from './api/box'
import { fetchCentralConfiguration } from './api/configuration'
import { fetchDirectory, type DirectoryView } from './api/directory'
import { fetchPriority } from './api/priority'
import { fetchInboundTransfers, fetchOverview, fetchPeers, fetchTransfers } from './api/transfers'

/** Requêtes de la console : mêmes clés partout, rafraîchies par le flux en direct (api/events.ts). */
export const useBox = () => useQuery({ queryKey: ['box'], queryFn: fetchBoxInfo, refetchInterval: 60_000 })
export const useOverview = () => useQuery({ queryKey: ['overview'], queryFn: fetchOverview, refetchInterval: 30_000 })
export const useTransfers = () => useQuery({ queryKey: ['transfers'], queryFn: fetchTransfers, refetchInterval: 30_000 })
export const useInbound = () => useQuery({ queryKey: ['inbound'], queryFn: fetchInboundTransfers, refetchInterval: 30_000 })
export const usePeers = () => useQuery({ queryKey: ['peers'], queryFn: fetchPeers, refetchInterval: 60_000 })
export const useDirectory = () => useQuery({ queryKey: ['directory'], queryFn: fetchDirectory, refetchInterval: 30_000 })
export const useConfiguration = () =>
  useQuery({ queryKey: ['central-configuration'], queryFn: fetchCentralConfiguration, refetchInterval: 30_000 })
export const usePriority = () => useQuery({ queryKey: ['priority'], queryFn: fetchPriority, refetchInterval: 60_000 })

/** Entrée de cette box dans l'annuaire (absente tant qu'elle n'est pas enrôlée). */
export function ownEntry(d: DirectoryView | undefined) {
  return d?.boxes.find((b) => b.boxId === d.boxId)
}

/** La box attend d'être enrôlée : annuaire activé, et pas encore d'entrée à son nom. */
export function awaitingEnrollment(d: DirectoryView | undefined): boolean {
  return !!d && d.enabled && !!d.boxId && !ownEntry(d)
}

/** Nom d'une box d'après l'annuaire, avec son organisation si elle est partenaire. */
export function boxLabel(d: DirectoryView | undefined, boxId: string): string {
  const b = d?.boxes.find((x) => x.boxId === boxId)
  if (!b) return boxId.slice(0, 8) + '…'
  return b.organization ? `${b.displayName} (${b.organization})` : b.displayName
}
