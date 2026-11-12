import { useQuery } from '@tanstack/react-query'
import { BOX_STATUS_LABELS, fetchDirectory } from '../api/directory'
import { fetchPeers, type Peer } from '../api/transfers'
import { formatDate } from '../format'
import { StateBadge } from './StateBadge'

function DirectoryBadge({ peer }: { peer: Peer }) {
  if (peer.boxId === null) {
    return <StateBadge label="non vérifié par l’annuaire" tone="warning" />
  }
  if (peer.directoryStatus === 'ACTIVE') {
    return <StateBadge label="vérifié par l’annuaire" tone="ok" />
  }
  return (
    <StateBadge label={peer.directoryStatus ? BOX_STATUS_LABELS[peer.directoryStatus] : 'absent de l’annuaire'} tone="error" />
  )
}

export function PeerList() {
  const peers = useQuery({ queryKey: ['peers'], queryFn: fetchPeers, refetchInterval: 60_000 })
  const directory = useQuery({ queryKey: ['directory'], queryFn: fetchDirectory, refetchInterval: 60_000 })
  const directoryEnabled = directory.isSuccess && directory.data.enabled

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
          <span className="muted small"> — appairé le {formatDate(p.pairedAt)} </span>
          {directoryEnabled && <DirectoryBadge peer={p} />}
        </li>
      ))}
    </ul>
  )
}
