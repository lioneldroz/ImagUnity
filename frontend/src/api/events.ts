import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'

export type StreamStatus = 'connecting' | 'live' | 'offline'

/**
 * Abonnement au flux SSE de la box (GET /api/v1/events) : chaque changement de transfert rafraîchit
 * les listes concernées. EventSource se reconnecte de lui-même après une coupure.
 */
export function useConsoleEvents(): StreamStatus {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<StreamStatus>('connecting')

  useEffect(() => {
    const source = new EventSource('/api/v1/events')
    const refresh = (keys: string[][]) => () => {
      keys.forEach((queryKey) => void queryClient.invalidateQueries({ queryKey }))
    }
    source.addEventListener('ready', () => {
      setStatus('live')
      // Après une reconnexion, des changements ont pu être manqués.
      void queryClient.invalidateQueries()
    })
    source.addEventListener('transfer', refresh([['transfers'], ['transfer'], ['overview']]))
    source.addEventListener('inbound', refresh([['inbound'], ['overview']]))
    source.onerror = () => setStatus('offline')
    return () => source.close()
  }, [queryClient])

  return status
}
