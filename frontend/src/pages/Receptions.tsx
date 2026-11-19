import { Fragment, useState } from 'react'
import type { InboundTransfer } from '../api/transfers'
import { Chevron, Etat, Filtres, Parcours, Pastille, Section } from '../components/ui'
import { useInbound } from '../data'
import { inLabel, inSteps, inTone } from '../etat'
import { quand, shortUid } from '../format'

type Filtre = 'toutes' | 'cours' | 'echec' | 'restituees'

const garde: Record<Filtre, (t: InboundTransfer) => boolean> = {
  toutes: () => true,
  cours: (t) => t.state === 'ANNOUNCED',
  echec: (t) => t.state === 'FAILED',
  restituees: (t) => t.state === 'RESTORED',
}

const cle = (t: InboundTransfer) => `${t.peerName}/${t.transferId}`

export function Receptions({ now }: { now: number }) {
  const transfers = useInbound()
  const [filtre, setFiltre] = useState<Filtre>('toutes')
  const [cherche, setCherche] = useState('')
  const [open, setOpen] = useState<string | null>(null)
  const all = transfers.data ?? []
  const q = cherche.trim().toLowerCase()
  const rows = all
    .filter(garde[filtre])
    .filter((t) => !q || t.studyInstanceUid.toLowerCase().includes(q) || t.peerName.toLowerCase().includes(q))
  const count = (f: Filtre) => all.filter(garde[f]).length

  return (
    <>
      <p className="sous-titre">
        Les {all.length < 100 ? all.length : 100} dernières réceptions. Une étude se déplie pour montrer son parcours.
      </p>
      <div className="barre-filtres">
        <Filtres
          label="Filtrer par état"
          actif={filtre}
          onChange={setFiltre}
          choix={[
            { key: 'toutes', label: 'Toutes', count: count('toutes') },
            { key: 'cours', label: 'En cours', count: count('cours') },
            { key: 'echec', label: 'En échec', count: count('echec') },
            { key: 'restituees', label: 'Restituées', count: count('restituees') },
          ]}
        />
        <label className="recherche">
          Rechercher
          <input type="search" placeholder="étude, émetteur" value={cherche} onChange={(e) => setCherche(e.target.value)} />
        </label>
      </div>
      <Section id="liste-receptions" title="Liste des réceptions" hiddenTitle>
        <Etat pending={transfers.isPending} error={transfers.error} />
        {transfers.isSuccess && rows.length === 0 && (
          <p className="discret">{all.length === 0 ? 'Aucune réception pour l’instant.' : 'Aucune réception ne correspond.'}</p>
        )}
        {rows.length > 0 && (
          <div className="table-scroll">
            <table className="liste" aria-label="Réceptions">
              <thead>
                <tr>
                  <th scope="col">Étude</th>
                  <th scope="col">Émetteur</th>
                  <th scope="col">État</th>
                  <th scope="col">Paquets restitués</th>
                  <th scope="col">Instances</th>
                  <th scope="col">Accusé</th>
                  <th scope="col">Mis à jour</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((t) => {
                  const k = cle(t)
                  const isOpen = open === k
                  const tone = inTone(t)
                  const pct = t.partCount > 0 ? Math.round((100 * t.restoredParts) / t.partCount) : 0
                  const id = `reception-${t.transferId}`
                  return (
                    <Fragment key={k}>
                      <tr className={tone === 'fail' ? 'ligne-echec' : undefined}>
                        <td>
                          <button
                            type="button"
                            className="row-toggle"
                            aria-expanded={isOpen}
                            aria-controls={id}
                            title={t.studyInstanceUid}
                            onClick={() => setOpen(isOpen ? null : k)}
                          >
                            <Chevron open={isOpen} />
                            {shortUid(t.studyInstanceUid)}
                          </button>
                        </td>
                        <td>{t.peerName}</td>
                        <td><Pastille tone={tone}>{inLabel(t)}</Pastille></td>
                        <td className="avec-barre">
                          <span>{t.restoredParts} / {t.partCount}</span>
                          <span className="barre" aria-hidden="true">
                            <span className={`barre-remplie${tone === 'fail' ? ' barre-echec' : ''}`} style={{ width: `${pct}%` }} />
                          </span>
                        </td>
                        <td>{t.restoredInstances}</td>
                        <td className="discret">{t.state === 'ANNOUNCED' ? '—' : t.ackSent ? 'envoyé' : 'à envoyer'}</td>
                        <td className="discret">{quand(t.updatedAt, now)}</td>
                      </tr>
                      {isOpen && (
                        <tr className="ligne-detail" id={id}>
                          <td colSpan={7}>
                            <Parcours etapes={inSteps(t, now)} />
                            {t.errorMessage && <p className="detail-echec">{t.errorMessage}</p>}
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
