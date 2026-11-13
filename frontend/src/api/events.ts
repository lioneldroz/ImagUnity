import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'

export type StreamStatus = 'connecting' | 'live' | 'offline'

/** Attente avant de rouvrir un flux fermé : doublée à chaque échec, jusqu'à une minute. */
const FIRST_RETRY_MS = 2_000
const MAX_RETRY_MS = 60_000

/**
 * Abonnement au flux SSE de la box (GET /api/v1/events) : chaque changement de transfert rafraîchit
 * les listes concernées.
 *
 * EventSource se reconnecte de lui-même après une coupure réseau, mais abandonne définitivement après une réponse
 * HTTP en erreur (502 d'un proxy pendant le redémarrage de la box, par exemple) : le flux est alors rouvert ici,
 * avec une attente croissante.
 */
export function useConsoleEvents(): StreamStatus {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<StreamStatus>('connecting')

  useEffect(() => {
    let source: EventSource | undefined
    let retry: ReturnType<typeof setTimeout> | undefined
    let delay = FIRST_RETRY_MS
    let stopped = false

    const refresh = (keys: string[][]) => () => {
      keys.forEach((queryKey) => void queryClient.invalidateQueries({ queryKey }))
    }

    const open = () => {
      const current = new EventSource('/api/v1/events')
      source = current
      current.addEventListener('ready', () => {
        setStatus('live')
        delay = FIRST_RETRY_MS
        // Après une reconnexion, des changements ont pu être manqués.
        void queryClient.invalidateQueries()
      })
      current.addEventListener('transfer', refresh([['transfers'], ['transfer'], ['overview']]))
      current.addEventListener('inbound', refresh([['inbound'], ['overview']]))
      current.onerror = () => {
        setStatus('offline')
        if (current.readyState === EventSource.CLOSED && !stopped) {
          retry = setTimeout(open, delay)
          delay = Math.min(delay * 2, MAX_RETRY_MS)
        }
      }
    }

    open()
    return () => {
      stopped = true
      clearTimeout(retry)
      source?.close()
    }
  }, [queryClient])

  return status
}
