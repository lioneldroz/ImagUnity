import type { ReactNode } from 'react'

/** Ton d'un état : réussi, en cours, neutre, à surveiller, en échec. */
export type Tone = 'ok' | 'progress' | 'neutral' | 'warn' | 'fail'

/** Point de couleur : décoratif, l'état est toujours écrit à côté (RGAA 3.1). */
export function Point({ tone, large }: { tone: Tone; large?: boolean }) {
  return <span className={`point point-${tone}${large ? ' point-large' : ''}`} aria-hidden="true" />
}

/** Pastille d'état : point et texte. */
export function Pastille({ tone, children }: { tone: Tone; children: ReactNode }) {
  return (
    <span className={`pastille pastille-${tone}`}>
      <Point tone={tone} />
      {children}
    </span>
  )
}

/** Carte de section, titrée. */
export function Section({ id, title, intro, aside, hiddenTitle, children }: {
  id: string
  title: string
  /** Titre lu par les lecteurs d'écran seulement (la page le dit déjà). */
  hiddenTitle?: boolean
  intro?: ReactNode
  aside?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="carte" aria-labelledby={id}>
      {hiddenTitle ? (
        <h2 id={id} className="visually-hidden">{title}</h2>
      ) : (
        <div className="carte-entete">
          <h2 id={id}>{title}</h2>
          {aside}
        </div>
      )}
      {intro && <p className="intro">{intro}</p>}
      {children}
    </section>
  )
}

/** Étape d'un parcours (envoi, réception, mise en service). */
export interface Etape {
  label: string
  /** fait, en cours, en échec ou à venir */
  state: 'ok' | 'progress' | 'fail' | 'todo'
  at?: string | null
}

export function Parcours({ etapes }: { etapes: Etape[] }) {
  return (
    <ol className="parcours" aria-label="Parcours">
      {etapes.map((e) => (
        <li key={e.label} className={`etape etape-${e.state}`}>
          <span className="etape-point" aria-hidden="true" />
          <span className="etape-label">
            {e.label}
            {e.state === 'fail' && <span className="visually-hidden"> (en échec)</span>}
            {e.state === 'progress' && <span className="visually-hidden"> (en cours)</span>}
            {e.state === 'todo' && <span className="visually-hidden"> (à venir)</span>}
          </span>
          {e.at && <span className="etape-heure">{e.at}</span>}
        </li>
      ))}
    </ol>
  )
}

/** Filtres par état : boutons pressés ou non, avec leur nombre. */
export function Filtres<K extends string>({ label, choix, actif, onChange }: {
  label: string
  choix: { key: K; label: string; count: number }[]
  actif: K
  onChange: (k: K) => void
}) {
  return (
    <div className="filtres" role="group" aria-label={label}>
      {choix.map((c) => (
        <button key={c.key} type="button" className="filtre" aria-pressed={c.key === actif} onClick={() => onChange(c.key)}>
          {c.label}
          <span className="filtre-compte">{c.count}</span>
        </button>
      ))}
    </div>
  )
}

/** Message en tête de page (état particulier de la box). */
export function Bandeau({ tone, children }: { tone: 'neutral' | 'warn' | 'fail'; children: ReactNode }) {
  return <p className={`message-etat message-${tone}`}>{children}</p>
}

/** Bouton qui déplie un détail, avec sa flèche ; l'état déplié est annoncé (aria-expanded). */
export function Deplier({ open, onToggle, controls, tone = 'fail' }: {
  open: boolean
  onToggle: () => void
  controls: string
  tone?: 'fail' | 'warn'
}) {
  return (
    <button type="button" className={`deplier deplier-${tone}`} aria-expanded={open} aria-controls={controls} onClick={onToggle}>
      <Chevron open={open} />
      {open ? 'Masquer' : 'Détail'}
    </button>
  )
}

/** Chargement ou erreur d'une requête, en clair. */
export function Etat({ pending, error }: { pending: boolean; error: Error | null }) {
  if (error) return <p className="erreur">Indisponible ({error.message})</p>
  if (pending) return <p className="discret">Chargement…</p>
  return null
}

/** Flèche d'un détail repliable : tournée vers le bas quand il est déplié. */
export function Chevron({ open }: { open: boolean }) {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className={`chevron${open ? ' ouvert' : ''}`}>
      <path d="M9 6l6 6-6 6" />
    </svg>
  )
}
