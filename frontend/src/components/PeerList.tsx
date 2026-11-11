import { useQuery } from '@tanstack/react-query'
import { fetchPeers } from '../api/transfers'
import { formatDate } from '../format'

export function PeerList() {
  const peers = useQuery({ queryKey: ['peers'], queryFn: fetchPeers, refetchInterval: 60_000 })

  if (peers.isError) {
    return <p className="error">Liste indisponible ({peers.error.message})</p>
  }
  if (!peers.isSuccess) {
    return <p className="muted">Chargement…</p>
  }
  if (peers.data.length === 0) {
    return <p className="muted">Aucun partenaire appairé.</p>
  }
  return (
    <ul className="peers">
      {peers.data.map((p) => (
        <li key={p.name}>
          <strong>{p.name}</strong>
          {p.displayName && p.displayName !== p.name && <span className="muted"> ({p.displayName})</span>}
          <span className="muted small"> — appairé le {formatDate(p.pairedAt)}</span>
        </li>
      ))}
    </ul>
  )
}
