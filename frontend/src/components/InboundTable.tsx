import { useQuery } from '@tanstack/react-query'
import { Fragment } from 'react'
import { fetchInboundTransfers, INBOUND_LABELS, type InboundState } from '../api/transfers'
import { formatDate, shortUid } from '../format'
import { StateBadge } from './StateBadge'

function tone(state: InboundState) {
  switch (state) {
    case 'RESTORED':
      return 'ok'
    case 'FAILED':
      return 'error'
    default:
      return 'progress'
  }
}

export function InboundTable() {
  const transfers = useQuery({ queryKey: ['inbound'], queryFn: fetchInboundTransfers, refetchInterval: 30_000 })

  if (transfers.isError) {
    return <p className="error">Liste indisponible ({transfers.error.message})</p>
  }
  if (!transfers.isSuccess) {
    return <p className="muted">Chargement…</p>
  }
  if (transfers.data.length === 0) {
    return <p className="muted">Aucune réception pour l'instant.</p>
  }
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>Étude</th>
            <th>Émetteur</th>
            <th>État</th>
            <th>Instances</th>
            <th>Accusé</th>
            <th>Mis à jour</th>
          </tr>
        </thead>
        <tbody>
          {transfers.data.map((t) => (
            <Fragment key={`${t.peerName}/${t.transferId}`}>
              <tr>
                <td className="mono" title={t.studyInstanceUid}>
                  {shortUid(t.studyInstanceUid)}
                </td>
                <td>{t.peerName}</td>
                <td>
                  <StateBadge label={INBOUND_LABELS[t.state]} tone={tone(t.state)} />
                </td>
                <td>{t.state === 'RESTORED' ? t.restoredInstances : '—'}</td>
                <td>{t.state === 'ANNOUNCED' ? '—' : t.ackSent ? 'envoyé' : 'à envoyer'}</td>
                <td>{formatDate(t.updatedAt)}</td>
              </tr>
              {t.errorMessage && (
                <tr className="error-row">
                  <td colSpan={6} className="error small">
                    {t.errorMessage}
                  </td>
                </tr>
              )}
            </Fragment>
          ))}
        </tbody>
      </table>
    </div>
  )
}
