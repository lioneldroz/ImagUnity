import { useQuery } from '@tanstack/react-query'
import { BOX_STATUS_LABELS, ENROLLMENT_REQUEST_URL, fetchDirectory } from '../api/directory'
import { formatDate } from '../format'
import { StateBadge } from './StateBadge'

export function DirectoryCard() {
  const directory = useQuery({ queryKey: ['directory'], queryFn: fetchDirectory, refetchInterval: 60_000 })

  if (directory.isError) {
    return <p className="error">Annuaire indisponible ({directory.error.message})</p>
  }
  if (!directory.isSuccess) {
    return <p className="muted">Chargement…</p>
  }
  const d = directory.data
  if (!d.enabled) {
    return <p className="muted">Annuaire non activé : tout partenaire appairé est accepté.</p>
  }
  const sync = d.lastSync
  return (
    <div className="directory">
      <dl>
        <dt>Identité de la box</dt>
        <dd className="mono">{d.boxId}</dd>
        <dt>Empreinte</dt>
        <dd className="mono">{d.shortCode}</dd>
        <dt>Enrôlement</dt>
        <dd>
          <a href={ENROLLMENT_REQUEST_URL} download>
            Télécharger la demande d’enrôlement
          </a>{' '}
          <span className="muted small">à remettre au responsable, qui vérifie l’empreinte avec vous</span>
        </dd>
        <dt>Synchronisation</dt>
        <dd>
          {!sync?.at && <span className="muted">pas encore faite</span>}
          {sync?.at && sync.error && <span className="error">{formatDate(sync.at)} : {sync.error}</span>}
          {sync?.at && !sync.error && (
            <span>
              {formatDate(sync.at)} : {sync.accepted} entrée(s) retenue(s)
              {sync.rejected > 0 && <span className="error"> , {sync.rejected} écartée(s)</span>}
            </span>
          )}
        </dd>
      </dl>
      {d.boxes.length > 0 && (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Box de l’organisation</th>
                <th>État</th>
                <th>Valide jusqu’au</th>
              </tr>
            </thead>
            <tbody>
              {d.boxes.map((b) => (
                <tr key={b.boxId}>
                  <td>
                    {b.displayName}
                    {b.boxId === d.boxId && <span className="muted small"> (cette box)</span>}
                  </td>
                  <td>
                    <StateBadge label={BOX_STATUS_LABELS[b.status]} tone={b.status === 'ACTIVE' ? 'ok' : 'error'} />
                  </td>
                  <td>{formatDate(b.validUntil)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
