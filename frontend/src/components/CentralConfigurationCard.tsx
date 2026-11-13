import { useQuery } from '@tanstack/react-query'
import { fetchCentralConfiguration } from '../api/configuration'
import { formatDate } from '../format'

/** Configuration centralisée appliquée (lecture seule) : version, provenance, dernier refus. */
export function CentralConfigurationCard() {
  const configuration = useQuery({
    queryKey: ['central-configuration'],
    queryFn: fetchCentralConfiguration,
    refetchInterval: 30_000,
  })
  if (configuration.isPending) {
    return <p className="muted">Chargement…</p>
  }
  if (configuration.isError) {
    return <p className="error">Configuration centralisée indisponible.</p>
  }
  const view = configuration.data
  if (!view.enabled) {
    return <p className="muted">Désactivée : la box applique sa configuration locale.</p>
  }
  return (
    <div>
      {view.sequence === 0 ? (
        <p className="muted">Aucune version reçue : la box applique sa configuration locale.</p>
      ) : (
        <>
          <p>
            Version <strong>n° {view.sequence}</strong>
            {view.issuedAt && <> du {formatDate(view.issuedAt)}</>}
            {view.author && <> par {view.author}</>}
            {view.appliedAt && <>, appliquée le {formatDate(view.appliedAt)}</>}.
          </p>
          {view.comment && <p className="muted small">Motif : {view.comment}</p>}
          <p className="small">
            Priorité des séries : {view.priority ? 'règles centrales' : 'règles locales'} — routage :{' '}
            {view.routing ? 'règles centrales' : 'règles locales'}.
          </p>
          {view.routingRules.length > 0 && (
            <ul className="mono small">
              {view.routingRules.map((rule) => (
                <li key={rule}>{rule}</li>
              ))}
            </ul>
          )}
        </>
      )}
      {view.refused > 0 && view.refusal && (
        <p className="error small">
          Version n° {view.refused} refusée, la configuration en vigueur reste appliquée : {view.refusal}
        </p>
      )}
      {view.error && <p className="error small">Dernière lecture impossible : {view.error}</p>}
      {view.checkedAt && <p className="muted small">Dernière lecture de l’annuaire : {formatDate(view.checkedAt)}</p>}
    </div>
  )
}
