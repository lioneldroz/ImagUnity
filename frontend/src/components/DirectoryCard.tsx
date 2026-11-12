import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { BOX_STATUS_LABELS, ENROLLMENT_REQUEST_URL, fetchDirectory, pairWithBox, type DirectoryBox } from '../api/directory'
import { formatDate } from '../format'
import { StateBadge } from './StateBadge'

/** Nom local proposé : le nom d’annuaire en minuscules, sans accents ni espaces. */
function suggestedName(displayName: string): string {
  return (
    displayName
      .normalize('NFD')
      .replace(/\p{M}/gu, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 60) || 'box'
  )
}

function PairCell({ box, own }: { box: DirectoryBox; own: boolean }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(suggestedName(box.displayName))
  const pair = useMutation({
    mutationFn: () => pairWithBox(box.boxId, name),
    onSuccess: () => {
      // La liaison se fait par la poignée de main signée, quelques secondes après la connexion.
      setTimeout(() => void queryClient.invalidateQueries(), 3000)
    },
  })
  if (own) {
    return <span className="muted small">cette box</span>
  }
  if (box.pairedAs) {
    return <span>{box.pairedAs}</span>
  }
  if (box.status !== 'ACTIVE' || !box.reachable) {
    return <span className="muted small">{box.reachable ? '—' : 'pas d’adresse publiée'}</span>
  }
  if (pair.isSuccess) {
    return <span className="muted small">connexion en cours, vérification…</span>
  }
  return (
    <form
      className="pair"
      onSubmit={(e) => {
        e.preventDefault()
        pair.mutate()
      }}
    >
      <input aria-label={`Nom local pour ${box.displayName}`} value={name} onChange={(e) => setName(e.target.value)} />
      <button type="submit" disabled={pair.isPending || name.length === 0}>
        Appairer
      </button>
      {pair.isError && <div className="error small">{pair.error.message}</div>}
    </form>
  )
}

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
                <th>Partenaire</th>
              </tr>
            </thead>
            <tbody>
              {d.boxes.map((b) => (
                <tr key={b.boxId}>
                  <td>{b.displayName}</td>
                  <td>
                    <StateBadge label={BOX_STATUS_LABELS[b.status]} tone={b.status === 'ACTIVE' ? 'ok' : 'error'} />
                    {b.status === 'ACTIVE' && !b.encrypted && (
                      <div className="error small">sans clés de chiffrement : entrée à réémettre</div>
                    )}
                  </td>
                  <td>{formatDate(b.validUntil)}</td>
                  <td>
                    <PairCell box={b} own={b.boxId === d.boxId} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
