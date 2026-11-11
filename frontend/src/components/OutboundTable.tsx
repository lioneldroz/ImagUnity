import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fragment, useState } from 'react'
import {
  fetchTransfer,
  fetchTransfers,
  OUTBOUND_LABELS,
  retryTransfer,
  type OutboundState,
  type OutboundTransfer,
} from '../api/transfers'
import { formatDate, formatSize, shortUid } from '../format'
import { StateBadge } from './StateBadge'

function tone(state: OutboundState) {
  switch (state) {
    case 'ACKNOWLEDGED':
      return 'ok'
    case 'FAILED':
      return 'error'
    default:
      return 'progress'
  }
}

function Parts({ id }: { id: string }) {
  const detail = useQuery({ queryKey: ['transfer', id], queryFn: () => fetchTransfer(id) })
  if (detail.isError) {
    return <p className="error">Détail indisponible ({detail.error.message})</p>
  }
  if (!detail.isSuccess) {
    return <p className="muted">Chargement…</p>
  }
  if (detail.data.parts.length === 0) {
    return <p className="muted">Pas encore de paquet.</p>
  }
  return (
    <table className="parts">
      <thead>
        <tr>
          <th>Paquet</th>
          <th>Fichier</th>
          <th>Taille</th>
          <th>SHA-256</th>
          <th>Dépôt</th>
        </tr>
      </thead>
      <tbody>
        {detail.data.parts.map((p) => (
          <tr key={p.index}>
            <td>
              {p.index}/{p.count}
            </td>
            <td className="mono">{p.fileName}</td>
            <td>{formatSize(p.size)}</td>
            <td className="mono" title={p.sha256}>
              {p.sha256.slice(0, 16)}…
            </td>
            <td>{p.uploaded ? 'déposé' : p.handedOver ? 'en cours' : 'en attente'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function RetryButton({ transfer }: { transfer: OutboundTransfer }) {
  const queryClient = useQueryClient()
  const retry = useMutation({
    mutationFn: () => retryTransfer(transfer.id),
    onSettled: () => queryClient.invalidateQueries(),
  })
  return (
    <>
      <button type="button" onClick={() => retry.mutate()} disabled={retry.isPending}>
        {retry.isPending ? 'Relance…' : 'Relancer'}
      </button>
      {retry.isError && <div className="error small">{retry.error.message}</div>}
    </>
  )
}

export function OutboundTable() {
  const transfers = useQuery({ queryKey: ['transfers'], queryFn: fetchTransfers, refetchInterval: 30_000 })
  const [open, setOpen] = useState<string | null>(null)

  if (transfers.isError) {
    return <p className="error">Liste indisponible ({transfers.error.message})</p>
  }
  if (!transfers.isSuccess) {
    return <p className="muted">Chargement…</p>
  }
  if (transfers.data.length === 0) {
    return <p className="muted">Aucun envoi pour l'instant.</p>
  }
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>Étude</th>
            <th>Destination</th>
            <th>État</th>
            <th>Taille</th>
            <th>Paquets</th>
            <th>Mis à jour</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {transfers.data.map((t) => (
            <Fragment key={t.id}>
              <tr className="clickable" onClick={() => setOpen(open === t.id ? null : t.id)}>
                <td className="mono" title={t.studyInstanceUid}>
                  {shortUid(t.studyInstanceUid)}
                </td>
                <td>{t.destination}</td>
                <td>
                  <StateBadge label={OUTBOUND_LABELS[t.state]} tone={tone(t.state)} />
                  {t.ackOverdue && <StateBadge label="accusé en retard" tone="warning" />}
                </td>
                <td>{formatSize(t.sizeBytes)}</td>
                <td>{t.partCount || '—'}</td>
                <td>{formatDate(t.updatedAt)}</td>
                <td onClick={(e) => e.stopPropagation()}>
                  {t.state === 'FAILED' && <RetryButton transfer={t} />}
                </td>
              </tr>
              {t.errorMessage && (
                <tr className="error-row">
                  <td colSpan={7} className="error small">
                    {t.errorMessage}
                  </td>
                </tr>
              )}
              {open === t.id && (
                <tr className="detail-row">
                  <td colSpan={7}>
                    <Parts id={t.id} />
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
