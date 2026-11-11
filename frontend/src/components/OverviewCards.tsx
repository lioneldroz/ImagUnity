import { useQuery } from '@tanstack/react-query'
import { fetchOverview, type Overview } from '../api/transfers'

function sum(counts: Partial<Record<string, number>>, keys: string[]): number {
  return keys.reduce((total, key) => total + (counts[key] ?? 0), 0)
}

interface Tile {
  label: string
  value: number
  tone?: 'error' | 'warning'
}

function tiles(o: Overview): Tile[] {
  return [
    { label: 'Envois en cours', value: sum(o.outbound, ['RECEIVED', 'PACKAGED', 'UPLOADED', 'ANNOUNCED']) },
    { label: 'Envois confirmés', value: sum(o.outbound, ['ACKNOWLEDGED']) },
    { label: 'Envois en échec', value: sum(o.outbound, ['FAILED']), tone: 'error' },
    { label: 'Accusés en retard', value: o.ackOverdue, tone: 'warning' },
    { label: 'Réceptions restituées', value: sum(o.inbound, ['RESTORED']) },
    { label: 'Réceptions en échec', value: sum(o.inbound, ['FAILED']), tone: 'error' },
    { label: 'Partenaires', value: o.peers },
  ]
}

export function OverviewCards() {
  const overview = useQuery({ queryKey: ['overview'], queryFn: fetchOverview, refetchInterval: 30_000 })

  if (overview.isError) {
    return <p className="error">Indicateurs indisponibles ({overview.error.message})</p>
  }
  if (!overview.isSuccess) {
    return <p className="muted">Chargement des indicateurs…</p>
  }
  return (
    <div className="tiles">
      {tiles(overview.data).map((tile) => (
        <div key={tile.label} className={`tile${tile.value > 0 && tile.tone ? ` tile-${tile.tone}` : ''}`}>
          <span className="tile-value">{tile.value}</span>
          <span className="tile-label">{tile.label}</span>
        </div>
      ))}
    </div>
  )
}
