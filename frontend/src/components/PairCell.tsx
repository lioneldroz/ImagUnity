import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { announce } from '../announce'
import { pairWithBox, type DirectoryBox } from '../api/directory'

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

export function PairCell({ box, own }: { box: DirectoryBox; own: boolean }) {
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

