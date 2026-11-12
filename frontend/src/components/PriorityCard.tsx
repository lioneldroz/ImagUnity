import { useQuery } from '@tanstack/react-query'
import { fetchPriority, type PriorityRuleView } from '../api/priority'
import { formatDate } from '../format'

function conditions(rule: PriorityRuleView): string {
  const and = Object.entries(rule.and).map(([k, v]) => `${k} = ${v}`)
  const or = Object.entries(rule.or).map(([k, v]) => `${k} = ${v}`)
  const parts = [...and]
  if (or.length > 0) {
    parts.push(and.length > 0 ? `(${or.join(' OR ')})` : or.join(' OR '))
  }
  return parts.length > 0 ? parts.join(' AND ') : 'toutes les séries'
}

/** Règles de priorité des séries en vigueur, en lecture seule. */
export function PriorityCard() {
  const priority = useQuery({ queryKey: ['priority'], queryFn: fetchPriority, refetchInterval: 30_000 })
  if (priority.isPending) {
    return <p className="muted">Chargement…</p>
  }
  if (priority.isError) {
    return <p className="error">Règles de priorité indisponibles.</p>
  }
  const view = priority.data
  return (
    <div>
      <p className="muted small">
        Source : {view.source}
        {view.loadedAt && <> — chargées le {formatDate(view.loadedAt)}</>} — priorité par défaut{' '}
        {view.defaultPriority}. Les séries prioritaires (1 = la plus haute) partent et sont restituées les premières.
      </p>
      {view.error && <p className="error small">Dernières règles refusées, les précédentes restent en vigueur : {view.error}</p>}
      {view.rules.length === 0 ? (
        <p className="muted">Aucune règle : les séries partent dans l’ordre d’arrivée.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Règle</th>
              <th>Priorité</th>
              <th>Conditions</th>
            </tr>
          </thead>
          <tbody>
            {view.rules.map((r) => (
              <tr key={r.name}>
                <td>{r.name}</td>
                <td>{r.priority}</td>
                <td className="mono small">{conditions(r)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
