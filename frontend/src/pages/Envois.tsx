import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fragment, useState } from 'react'
import { announce } from '../announce'
import { fetchTransfer, OUTBOUND_LABELS, retryTransfer, type OutboundTransfer } from '../api/transfers'
import { Chevron, Etat, Filtres, Parcours, Pastille, Section } from '../components/ui'
import { useTransfers } from '../data'
import { outSteps, outTone } from '../etat'
import { formatSize, quand, shortUid } from '../format'

type Filtre = 'tous' | 'cours' | 'retard' | 'echec' | 'confirmes'

const garde: Record<Filtre, (t: OutboundTransfer) => boolean> = {
  tous: () => true,
  cours: (t) => t.state !== 'FAILED' && t.state !== 'ACKNOWLEDGED',
  retard: (t) => t.ackOverdue,
  echec: (t) => t.state === 'FAILED',
  confirmes: (t) => t.state === 'ACKNOWLEDGED',
}

function Detail({ t, now }: { t: OutboundTransfer; now: number }) {
  const detail = useQuery({ queryKey: ['transfer', t.id], queryFn: () => fetchTransfer(t.id) })
  const parts = detail.data?.parts
  return (
    <>
      <Parcours etapes={outSteps(t, parts, now)} />
      {t.errorMessage && <p className="detail-echec">{t.errorMessage}</p>}
      <Etat pending={detail.isPending} error={detail.error} />
      {parts && parts.length === 0 && <p className="discret">Pas encore de paquet.</p>}
      {parts && parts.length > 0 && (
        <div className="table-scroll">
          <table className="paquets" aria-label={`Paquets de l’envoi de l’étude ${shortUid(t.studyInstanceUid)}`}>
            <thead>
              <tr>
                <th scope="col">Paquet</th>
                <th scope="col">Fichier</th>
                <th scope="col">Taille</th>
                <th scope="col">SHA-256</th>
                <th scope="col">Dépôt</th>
              </tr>
            </thead>
            <tbody>
              {parts.map((p) => (
                <tr key={p.index}>
                  <td>{p.index} / {p.count}</td>
                  <td className="mono">{p.fileName}</td>
                  <td className="discret">{formatSize(p.size)}</td>
                  <td className="mono discret" title={p.sha256}>{p.sha256.slice(0, 16)}…</td>
                  <td>
                    {p.uploaded ? (
                      <Pastille tone="ok">déposé</Pastille>
                    ) : p.handedOver ? (
                      <Pastille tone="progress">en cours</Pastille>
                    ) : t.state === 'FAILED' ? (
                      <Pastille tone="fail">non déposé</Pastille>
                    ) : (
                      <Pastille tone="neutral">en attente</Pastille>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function RetryButton({ transfer }: { transfer: OutboundTransfer }) {
  const queryClient = useQueryClient()
  const retry = useMutation({
    mutationFn: () => retryTransfer(transfer.id),
    onSuccess: () => announce('Envoi relancé.'),
    onError: (error) => announce(`Relance impossible : ${error.message}`),
    onSettled: async () => {
      await queryClient.invalidateQueries()
      // Relancé, l'envoi n'est plus en échec et ce bouton disparaît : le focus passe à l'étude de la même ligne
      // au lieu de retomber en haut de la page (RGAA 7.3).
      requestAnimationFrame(() => {
        if (document.activeElement === document.body || document.activeElement === null) {
          document.getElementById(`etude-${transfer.id}`)?.focus()
        }
      })
    },
  })
  return (
    <>
      <button type="button" className="bouton-secondaire" onClick={() => retry.mutate()} disabled={retry.isPending}>
        {retry.isPending ? 'Relance…' : 'Relancer'}
      </button>
      {retry.isError && <div className="erreur petit">{retry.error.message}</div>}
    </>
  )
}

export function Envois({ now }: { now: number }) {
  const transfers = useTransfers()
  const [filtre, setFiltre] = useState<Filtre>('tous')
  const [cherche, setCherche] = useState('')
  const [open, setOpen] = useState<string | null>(null)
  const all = transfers.data ?? []
  const q = cherche.trim().toLowerCase()
  const rows = all
    .filter(garde[filtre])
    .filter((t) => !q || t.studyInstanceUid.toLowerCase().includes(q) || t.destination.toLowerCase().includes(q))
  const count = (f: Filtre) => all.filter(garde[f]).length

  return (
    <>
      <p className="sous-titre">
        Les {all.length < 100 ? all.length : 100} derniers envois. Une étude se déplie pour montrer son parcours et ses paquets.
      </p>
      <div className="barre-filtres">
        <Filtres
          label="Filtrer par état"
          actif={filtre}
          onChange={setFiltre}
          choix={[
            { key: 'tous', label: 'Tous', count: count('tous') },
            { key: 'cours', label: 'En cours', count: count('cours') },
            { key: 'retard', label: 'Accusé en retard', count: count('retard') },
            { key: 'echec', label: 'En échec', count: count('echec') },
            { key: 'confirmes', label: 'Confirmés', count: count('confirmes') },
          ]}
        />
        <label className="recherche">
          Rechercher
          <input type="search" placeholder="étude, destination" value={cherche} onChange={(e) => setCherche(e.target.value)} />
        </label>
      </div>
      <Section id="liste-envois" title="Liste des envois" hiddenTitle>
        <Etat pending={transfers.isPending} error={transfers.error} />
        {transfers.isSuccess && rows.length === 0 && (
          <p className="discret">{all.length === 0 ? 'Aucun envoi pour l’instant.' : 'Aucun envoi ne correspond.'}</p>
        )}
        {rows.length > 0 && (
          <div className="table-scroll">
            <table className="liste" aria-label="Envois">
              <thead>
                <tr>
                  <th scope="col">Étude</th>
                  <th scope="col">Destination</th>
                  <th scope="col">État</th>
                  <th scope="col">Paquets</th>
                  <th scope="col">Taille</th>
                  <th scope="col">Mis à jour</th>
                  <th scope="col"><span className="visually-hidden">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((t) => {
                  const isOpen = open === t.id
                  const tone = outTone(t)
                  return (
                    <Fragment key={t.id}>
                      <tr className={tone === 'fail' ? 'ligne-echec' : undefined}>
                        <td>
                          {/* Le détail s'ouvre au clavier par ce bouton, qui dit s'il est ouvert (RGAA 7.1, 7.3). */}
                          <button
                            type="button"
                            id={`etude-${t.id}`}
                            className="row-toggle"
                            aria-expanded={isOpen}
                            aria-controls={`paquets-${t.id}`}
                            title={t.studyInstanceUid}
                            onClick={() => setOpen(isOpen ? null : t.id)}
                          >
                            <Chevron open={isOpen} />
                            {shortUid(t.studyInstanceUid)}
                          </button>
                          <div className="sous-ligne">AET appelé {t.calledAet}</div>
                        </td>
                        <td>{t.destination}</td>
                        <td>
                          <div className="pastilles">
                            <Pastille tone={tone}>{OUTBOUND_LABELS[t.state]}</Pastille>
                            {t.ackOverdue && <Pastille tone="warn">accusé en retard</Pastille>}
                          </div>
                        </td>
                        <td>{t.partCount || '—'}</td>
                        <td className="discret">{formatSize(t.sizeBytes)}</td>
                        <td className="discret">{quand(t.updatedAt, now)}</td>
                        <td className="actions">
                          {/* Accusé en retard : l'accusé ou l'annonce a pu se perdre, la relance renvoie tout. */}
                          {(t.state === 'FAILED' || t.ackOverdue) && <RetryButton transfer={t} />}
                        </td>
                      </tr>
                      {isOpen && (
                        <tr className="ligne-detail" id={`paquets-${t.id}`}>
                          <td colSpan={7}>
                            <Detail t={t} now={now} />
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </Section>
    </>
  )
}

