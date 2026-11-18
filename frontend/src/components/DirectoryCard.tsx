import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { announce } from '../announce'
import {
  BOX_STATUS_LABELS,
  ENROLLMENT_REQUEST_URL,
  fetchDirectory,
  pairWithBox,
  type DirectoryBox,
  type Freshness,
  type PartnerOrganization,
} from '../api/directory'
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
  // Cellule qui reste en place quand son contenu change (formulaire, attente, partenaire lié) : elle reçoit le focus
  // quand le formulaire disparaît, au lieu de le laisser retomber en haut de la page (RGAA 7.3).
  const zone = useRef<HTMLDivElement>(null)
  const pair = useMutation({
    mutationFn: () => pairWithBox(box.boxId, name),
    onError: (error) => announce(`Appairage avec ${box.displayName} impossible : ${error.message}`),
    onSuccess: () => {
      zone.current?.focus()
      announce(`Appairage avec ${box.displayName} demandé : connexion en cours, vérification…`)
      // La liaison se fait par la poignée de main signée, quelques secondes après la connexion.
      setTimeout(() => void queryClient.invalidateQueries(), 3000)
    },
  })
  // Sans liaison au bout d'une minute (poignée de main en échec, box injoignable), le formulaire revient : sinon
  // l'opérateur resterait sur « connexion en cours » sans pouvoir réessayer avant de recharger la page.
  const [unconfirmed, setUnconfirmed] = useState(false)
  const { isSuccess: pairRequested, reset: resetPair } = pair
  useEffect(() => {
    if (!pairRequested) return
    const timer = setTimeout(() => {
      resetPair()
      setUnconfirmed(true)
      announce(`${box.displayName} : pas encore liée après une minute, vérifier la box d’en face, puis réessayer`)
    }, 60_000)
    return () => clearTimeout(timer)
  }, [pairRequested, resetPair, box.displayName])
  const erreur = `appairage-erreur-${box.boxId}`
  let content
  if (own) {
    content = <span className="muted small">cette box</span>
  } else if (box.pairedAs) {
    content = <span>{box.pairedAs}</span>
  } else if (box.refusal) {
    content = <span className="muted small">{box.refusal}</span>
  } else if (box.status !== 'ACTIVE' || !box.reachable) {
    content = <span className="muted small">{box.reachable ? '—' : 'pas d’adresse publiée'}</span>
  } else if (pair.isSuccess) {
    content = <span className="muted small">connexion en cours, vérification…</span>
  } else {
    content = (
      <form
        className="pair"
        onSubmit={(e) => {
          e.preventDefault()
          pair.mutate()
        }}
      >
        {/* Étiquette visible (RGAA 11.1) ; le nom accessible la reprend, avec la box concernée. */}
        <label className="pair-label">
          <span className="small">Nom local</span>
          <input
            aria-label={`Nom local pour ${box.displayName}`}
            aria-describedby={pair.isError ? erreur : undefined}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <button type="submit" disabled={pair.isPending || name.length === 0}>
          Appairer
        </button>
        {pair.isError && (
          <div className="error small" id={erreur}>
            {pair.error.message}
          </div>
        )}
        {unconfirmed && !pair.isError && (
          <div className="muted small">pas encore lié après une minute : vérifier la box d’en face, puis réessayer</div>
        )}
      </form>
    )
  }
  return (
    <div ref={zone} tabIndex={-1} className="pair-cell">
      {content}
    </div>
  )
}

function FreshnessState({ f }: { f: Freshness | null }) {
  if (!f) return <span className="muted">non contrôlée</span>
  if (!f.issuedAt) return <span className="muted">{f.error ?? 'pas encore de preuve'}</span>
  return (
    <span className={f.stale ? 'error' : undefined}>
      preuve du {formatDate(f.issuedAt)}
      {f.stale && ' : trop ancienne, échanges arrêtés'}
      {f.error && <span className="error"> ({f.error})</span>}
    </span>
  )
}

function scope(groups: string[]): string {
  if (groups.length === 0) return 'aucun'
  return groups.includes('*') ? 'tous' : groups.join(', ')
}

function PartnersTable({ partners }: { partners: PartnerOrganization[] }) {
  return (
    <div className="table-scroll">
      <table aria-label="Organisations partenaires">
        <thead>
          <tr>
            <th scope="col">Organisation partenaire</th>
            <th scope="col">Accord</th>
            <th scope="col">Périmètre</th>
            <th scope="col">Dernière lecture</th>
          </tr>
        </thead>
        <tbody>
          {partners.map((p) => (
            <tr key={p.shortCode}>
              <td>
                {p.organization}
                <div className="muted small mono">{p.shortCode}</div>
              </td>
              <td>
                <StateBadge label={BOX_STATUS_LABELS[p.status]} tone={p.status === 'ACTIVE' ? 'ok' : 'error'} />
                <div className="muted small">jusqu’au {formatDate(p.validUntil)}</div>
              </td>
              <td className="small">
                groupes locaux : {scope(p.localGroups)}
                <br />
                groupes du partenaire : {scope(p.partnerGroups)}
                <div className="muted">relais : {p.relays.join(', ')}</div>
              </td>
              <td className="small">
                {!p.lastSync && <span className="muted">pas encore lue</span>}
                {p.lastSync?.error && <span className="error">{p.lastSync.error}</span>}
                {p.lastSync && !p.lastSync.error && p.lastSync.at && (
                  <span>
                    {formatDate(p.lastSync.at)} : {p.boxes} box utilisable(s)
                  </span>
                )}
                <div>
                  fraîcheur : <FreshnessState f={p.freshness} />
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
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
        <dt>Fraîcheur de l’annuaire</dt>
        <dd>
          <FreshnessState f={d.freshness} />
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
      {d.collisions.length > 0 && (
        <div className="error small">
          {d.collisions.length} identifiant(s) de box revendiqué(s) par deux organisations : entrée(s) écartée(s). Dernier :{' '}
          box {d.collisions[d.collisions.length - 1].boxId}, revendiquée par {d.collisions[d.collisions.length - 1].claimant},
          tenue par {d.collisions[d.collisions.length - 1].holder}. Prévenir le responsable.
        </div>
      )}
      {d.partners.length > 0 && <PartnersTable partners={d.partners} />}
      {d.boxes.length > 0 && (
        <div className="table-scroll">
          <table aria-label="Box de l’annuaire">
            <thead>
              <tr>
                <th scope="col">Box</th>
                <th scope="col">État</th>
                <th scope="col">Valide jusqu’au</th>
                <th scope="col">Partenaire</th>
              </tr>
            </thead>
            <tbody>
              {d.boxes.map((b) => (
                <tr key={b.boxId}>
                  <td>
                    {b.displayName}
                    {b.organization && <div className="muted small">organisation partenaire : {b.organization}</div>}
                    {/* Une seule version (iu1.0 partout) : rien à signaler ; un refus s'affiche à part. */}
                    {b.protocols && b.protocols.length > 1 && (
                      <div className="muted small" title="Versions du protocole annoncées par la box">
                        protocole {b.protocol ?? 'aucun en commun'} ({b.protocols.join(', ')})
                      </div>
                    )}
                  </td>
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
