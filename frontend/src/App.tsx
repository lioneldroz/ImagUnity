import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useConsoleEvents, type StreamStatus } from './api/events'
import { Bandeau, type Tone } from './components/ui'
import {
  awaitingEnrollment, ownEntry, useBox, useConfiguration, useDirectory, useInbound, usePeers, useTransfers,
} from './data'
import { appareilsEnEchec, aSurveiller } from './etat'
import { formatDate, ilYa } from './format'
import { Activite } from './pages/Activite'
import { Configuration } from './pages/Configuration'
import { Envois } from './pages/Envois'
import { MiseEnService } from './pages/MiseEnService'
import { Partenaires } from './pages/Partenaires'
import { Receptions } from './pages/Receptions'

type PageKey = 'activite' | 'envois' | 'receptions' | 'partenaires' | 'configuration'

const PAGES: { key: PageKey; label: string }[] = [
  { key: 'activite', label: 'Activité' },
  { key: 'envois', label: 'Envois' },
  { key: 'receptions', label: 'Réceptions' },
  { key: 'partenaires', label: 'Partenaires' },
  { key: 'configuration', label: 'Configuration' },
]

const TITRES: Record<PageKey | 'mes', string> = {
  activite: 'Activité de la box',
  envois: 'Envois',
  receptions: 'Réceptions',
  partenaires: 'Partenaires',
  configuration: 'Configuration',
  mes: 'Mise en service',
}

const STREAM: Record<StreamStatus, { tone: Tone; label: string }> = {
  connecting: { tone: 'neutral', label: 'Connexion…' },
  live: { tone: 'ok', label: 'En direct' },
  offline: { tone: 'fail', label: 'Hors ligne, reconnexion…' },
}

function pageFromHash(): PageKey {
  const h = window.location.hash.replace(/^#/, '')
  return (PAGES.find((p) => p.key === h)?.key ?? 'activite') as PageKey
}

function useHashPage(): PageKey {
  const [page, setPage] = useState(pageFromHash)
  useEffect(() => {
    const on = () => setPage(pageFromHash())
    window.addEventListener('hashchange', on)
    return () => window.removeEventListener('hashchange', on)
  }, [])
  return page
}

/** Heure courante, rafraîchie toutes les 30 s : les « il y a 3 min » restent justes. */
function useNow(): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30_000)
    return () => clearInterval(t)
  }, [])
  return now
}

interface Message {
  key: string
  tone: 'neutral' | 'warn' | 'fail'
  text: ReactNode
}

function App() {
  const now = useNow()
  const page = useHashPage()
  const stream = useConsoleEvents()
  const box = useBox()
  const directory = useDirectory()
  const configuration = useConfiguration()
  const transfers = useTransfers()
  const inbound = useInbound()
  const peers = usePeers()
  const d = directory.data
  const own = ownEntry(d)
  const mes = awaitingEnrollment(d)
  const shown: PageKey | 'mes' = mes ? 'mes' : page
  const titre = useRef<HTMLHeadingElement>(null)
  const premier = useRef(true)

  useEffect(() => {
    document.title = `${TITRES[shown]} · ${box.data?.name ?? 'Console de la box'}`
  }, [shown, box.data?.name])
  // Changement de page : son titre reçoit le focus, comme au chargement d'une page (RGAA 12.6). Pas au premier affichage.
  useEffect(() => {
    if (premier.current) {
      premier.current = false
      return
    }
    titre.current?.focus()
  }, [shown])

  // Pastille de l'annuaire, dans le bandeau.
  let annuaire: { tone: Tone; label: string } | null = null
  if (d && !d.enabled) annuaire = { tone: 'neutral', label: 'Annuaire non activé' }
  else if (d) {
    const sync = d.lastSync
    if (own?.status === 'REVOKED') annuaire = { tone: 'fail', label: 'Box révoquée' }
    else if (own?.status === 'EXPIRED') annuaire = { tone: 'fail', label: 'Box expirée' }
    else if (d.freshness?.stale) annuaire = { tone: 'fail', label: 'Échanges suspendus' }
    else if (mes) annuaire = { tone: 'warn', label: 'En attente d’enrôlement' }
    else if (sync?.error) annuaire = { tone: 'warn', label: sync.at ? `Annuaire injoignable (lu ${ilYa(sync.at, now)})` : 'Annuaire injoignable' }
    else if (sync?.at) annuaire = { tone: 'ok', label: `Annuaire à jour (${ilYa(sync.at, now)})` }
    else annuaire = { tone: 'neutral', label: 'Annuaire pas encore lu' }
  }

  // Messages en tête de page : ce qui ne va pas, et quoi faire.
  const messages: Message[] = []
  if (box.isError) {
    messages.push({ key: 'backend', tone: 'fail', text: `Le serveur de la box ne répond pas (${box.error.message}). Les informations affichées peuvent être anciennes.` })
  } else if (stream === 'offline') {
    messages.push({ key: 'flux', tone: 'neutral', text: 'Les listes ne se mettent plus à jour d’elles-mêmes. La console se reconnecte seule ; les envois et réceptions continuent sur la box.' })
  }
  if (box.data && box.data.releaseSequence === null) {
    messages.push({ key: 'version', tone: 'fail', text: `La version ${box.data.version} ne porte pas de signature valide de l’éditeur : elle n’a pas été livrée par la chaîne de mise à jour signée. Prévenez l’exploitant de la box.` })
  }
  if (own?.status === 'REVOKED' || own?.status === 'EXPIRED') {
    messages.push({
      key: 'revoquee', tone: 'fail',
      text: own.status === 'REVOKED'
        ? 'Le responsable a retiré cette box de l’annuaire : elle n’échange plus avec personne. Pour la remettre en service, il faut un nouvel enrôlement.'
        : `L’entrée de cette box dans l’annuaire a expiré le ${formatDate(own.validUntil)} : elle n’échange plus. Le responsable doit la renouveler.`,
    })
  }
  if (d?.enabled && d.freshness?.stale) {
    messages.push({
      key: 'fraicheur', tone: 'fail',
      text: `Plus de preuve de fraîcheur récente${d.freshness.issuedAt ? ` (dernière : ${formatDate(d.freshness.issuedAt)})` : ''} : les envois et réceptions sont arrêtés et reprendront seuls dès qu’une preuve récente sera lue. Prévenez le responsable.`,
    })
  } else if (d?.enabled && d.lastSync?.error) {
    messages.push({
      key: 'annuaire', tone: 'warn',
      text: `La box n’arrive pas à lire l’annuaire (${d.lastSync.error}). Elle travaille avec sa dernière copie${d.freshness?.issuedAt ? ` (preuve de fraîcheur ${ilYa(d.freshness.issuedAt, now)})` : ''}. Vérifiez la connexion du site vers ${d.setup?.directoryUrl ?? 'l’annuaire'}.`,
    })
  }
  const conf = configuration.data
  if (conf && conf.refused > 0 && conf.refusal) {
    messages.push({ key: 'refus', tone: 'warn', text: `La configuration n° ${conf.refused} a été refusée : ${conf.refusal}. ${conf.sequence > 0 ? `La n° ${conf.sequence} reste appliquée.` : 'La configuration locale reste appliquée.'} Le responsable le voit dans son interface.` })
  }

  // Pastilles du menu.
  const enCours = transfers.data?.filter((t) => t.state !== 'FAILED' && t.state !== 'ACKNOWLEDGED').length ?? 0
  const inCours = inbound.data?.filter((t) => t.state === 'ANNOUNCED').length ?? 0
  const points = aSurveiller(transfers.data, inbound.data, conf, d, now).length
  const echecs = appareilsEnEchec(conf?.devices).length
  const badges: Record<PageKey, { n: number; sens: string }> = {
    activite: { n: points, sens: 'à surveiller' },
    envois: { n: enCours, sens: 'en cours' },
    receptions: { n: inCours, sens: 'en cours' },
    partenaires: { n: peers.data?.length ?? 0, sens: 'appairés' },
    configuration: { n: echecs, sens: 'appareils en échec' },
  }

  const b = box.data
  const s = STREAM[stream]
  return (
    <div className="console">
      {/* Région de statut des annonces aux lecteurs d'écran (voir announce.ts), toujours présente et vide au départ. */}
      <div id="annonces" role="status" className="visually-hidden" />
      {/* Lien d'évitement (RGAA 12.7) : il déplace le focus sans toucher à l'adresse, qui désigne la page affichée. */}
      <a
        className="evitement"
        href="#contenu"
        onClick={(e) => {
          e.preventDefault()
          titre.current?.focus()
        }}
      >
        Aller au contenu
      </a>
      <header className="bandeau">
        <div className="bandeau-identite">
          <span className="logo" aria-hidden="true">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <rect x="3" y="4" width="18" height="7" rx="2" />
              <rect x="3" y="13" width="18" height="7" rx="2" />
              <path d="M7 7.5h.01M7 16.5h.01" />
            </svg>
          </span>
          <div className="bandeau-nom">
            <span className="nom-box">{b?.name ?? 'ImagUnity'}</span>
            <span className="nom-console">Console de la box{d?.setup?.organization ? ` · ${d.setup.organization}` : ''}</span>
          </div>
          {b && (
            <span className={`version mono${b.releaseSequence === null ? ' version-ko' : ''}`}>
              version {b.version} · {b.releaseSequence === null ? 'non vérifiée' : `signée n° ${b.releaseSequence}`}
            </span>
          )}
        </div>
        <div className="bandeau-etats">
          {/* État du flux en direct : annoncé quand il change (RGAA 7.5). */}
          <span role="status" className={`pastille-bandeau pastille-${s.tone}`}>
            <span className={`point point-${s.tone}`} aria-hidden="true" />
            {s.label}
          </span>
          {annuaire && (
            <span className={`pastille-bandeau pastille-${annuaire.tone}${annuaire.tone === 'ok' ? ' pastille-claire' : ''}`}>
              <span className={`point point-${annuaire.tone}`} aria-hidden="true" />
              {annuaire.label}
            </span>
          )}
        </div>
      </header>

      <div className="corps">
        <nav aria-label="Sections" className="menu">
          <ul>
            {mes && (
              <li>
                <a href="#activite" aria-current="page" className="menu-lien actif">Mise en service</a>
              </li>
            )}
            {PAGES.map((p) => {
              const badge = badges[p.key]
              const actif = !mes && p.key === page
              return (
                <li key={p.key}>
                  {mes ? (
                    <span className="menu-lien inactif" aria-disabled="true">{p.label}</span>
                  ) : (
                    <a href={`#${p.key}`} aria-current={actif ? 'page' : undefined} className={`menu-lien${actif ? ' actif' : ''}`}>
                      <span>{p.label}</span>
                      {badge.n > 0 && (
                        <span className="menu-badge">
                          {badge.n}
                          <span className="visually-hidden"> {badge.sens}</span>
                        </span>
                      )}
                    </a>
                  )}
                </li>
              )
            })}
          </ul>
          {mes ? (
            <p className="menu-note">Les autres sections s’ouvrent dès que le responsable a validé la box.</p>
          ) : (
            b && (
              <div className="menu-encart">
                <strong>Mises à jour</strong>
                <br />
                {b.releaseSequence === null
                  ? `Version ${b.version}, non vérifiée.`
                  : `Version ${b.version}, signée par l’éditeur (n° ${b.releaseSequence}).`}
              </div>
            )
          )}
        </nav>

        <main id="contenu" className="contenu">
          <h1 ref={titre} tabIndex={-1}>{TITRES[shown]}</h1>
          {messages.map((m) => (
            <Bandeau key={m.key} tone={m.tone}>{m.text}</Bandeau>
          ))}
          {shown === 'mes' && d && <MiseEnService d={d} now={now} />}
          {shown === 'activite' && <Activite now={now} />}
          {shown === 'envois' && <Envois now={now} />}
          {shown === 'receptions' && <Receptions now={now} />}
          {shown === 'partenaires' && <Partenaires now={now} />}
          {shown === 'configuration' && <Configuration now={now} />}
        </main>
      </div>
    </div>
  )
}

export default App
