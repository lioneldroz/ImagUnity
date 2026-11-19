import { useState } from 'react'
import type { DicomDevices, DicomNode, DicomRoute } from '../api/configuration'
import { ENROLLMENT_REQUEST_URL, type DirectoryView } from '../api/directory'
import type { PriorityRuleView } from '../api/priority'
import { Deplier, Etat, Pastille, Point, Section } from '../components/ui'
import { boxLabel, useConfiguration, useDirectory, usePriority } from '../data'
import { appareilsEnEchec, essais, etatAppareil } from '../etat'
import { dans, ilYa, quand } from '../format'

function conditions(rule: PriorityRuleView): string {
  const and = Object.entries(rule.and).map(([k, v]) => `${k} = ${v}`)
  const or = Object.entries(rule.or).map(([k, v]) => `${k} = ${v}`)
  const parts = [...and]
  if (or.length > 0) parts.push(and.length > 0 ? `(${or.join(' OR ')})` : or.join(' OR '))
  return parts.length > 0 ? parts.join(' AND ') : 'toutes les séries'
}

function Appareil({ n, aet, now }: { n: DicomNode; aet: string | null; now: number }) {
  const [open, setOpen] = useState(false)
  const etats = etatAppareil(n, aet, now)
  const echec = etats.some((e) => e.tone === 'fail')
  const id = `appareil-${n.name}`
  return (
    <li className={`carte-element${echec ? ' carte-echec' : ''}`}>
      <h3>{n.name}</h3>
      <span className="mono">
        {n.aet}
        {n.host && ` · ${n.host}${n.port ? `:${n.port}` : ''}`}
      </span>
      <div className="pastilles">
        {n.source && <span className="role role-source">↗ source</span>}
        {n.destination && <span className="role role-destination">↙ destination</span>}
      </div>
      {etats.map((e) => (
        <div key={e.label} className="pastilles">
          <Pastille tone={e.tone}>{e.label}</Pastille>
          {e.detail && <Deplier open={open} onToggle={() => setOpen(!open)} controls={id} tone={e.tone === 'fail' ? 'fail' : 'warn'} />}
        </div>
      ))}
      {open && (
        <p id={id} className="detail-echec">
          {etats.filter((e) => e.detail).map((e) => e.detail).join(' ')}
        </p>
      )}
    </li>
  )
}

function Appareils({ devices, now }: { devices: DicomDevices; now: number }) {
  const { last, next } = essais(devices)
  const echecs = appareilsEnEchec(devices).length
  return (
    <Section id="appareils" title="Appareils du site" intro={devices.aet ? `AET de la box : ${devices.aet}.` : undefined}>
      {last && (
        <p className="etat-ligne">
          <Point tone={echecs > 0 ? 'fail' : 'ok'} />
          Vérifié {ilYa(last, now)} :{' '}
          {echecs > 0
            ? `${echecs} destination${echecs > 1 ? 's ne répondent' : ' ne répond'} pas.`
            : 'toutes les destinations répondent.'}
          {next && ` Prochain essai ${dans(next, now)}.`}
        </p>
      )}
      <ul className="grille-cartes">
        {devices.nodes.map((n) => (
          <Appareil key={n.name} n={n} aet={devices.aet} now={now} />
        ))}
      </ul>
    </Section>
  )
}

function Routes({ id, title, routes, from, to }: {
  id: string
  title: string
  routes: DicomRoute[]
  from: (x: string) => string
  to: (x: string) => string
}) {
  return (
    <Section id={id} title={title}>
      {routes.length === 0 ? (
        <p className="discret">Aucune route.</p>
      ) : (
        <ol className="regles">
          {routes.map((r, i) => (
            <li key={r.name} className="regle">
              <span className="regle-rang mono" aria-hidden="true">{i + 1}</span>
              <span className="regle-nom">{r.name}</span>
              <span className="etiquette etiquette-source">{r.from.map(from).join(', ') || 'toute origine'}</span>
              <span className="fleche" aria-hidden="true">→</span>
              <span className="etiquette etiquette-filtre mono">
                {[r.calledAet && `AET appelé ${r.calledAet}`, r.filter].filter(Boolean).join(' · ') || 'toutes les études'}
              </span>
              <span className="fleche" aria-hidden="true">→</span>
              <span className="etiquette etiquette-cible">{r.to.map(to).join(', ')}</span>
            </li>
          ))}
        </ol>
      )}
    </Section>
  )
}

function Identite({ d }: { d: DirectoryView }) {
  return (
    <Section id="identite" title="Identité de la box">
      <dl className="liste-definitions">
        <dt>Identifiant</dt>
        <dd className="mono">{d.boxId}</dd>
        <dt>Empreinte</dt>
        <dd className="mono">{d.shortCode}</dd>
        <dt>Enrôlement</dt>
        <dd>
          <a href={ENROLLMENT_REQUEST_URL} download>Télécharger la demande d’enrôlement</a>{' '}
          <span className="discret">à remettre au responsable, qui vérifie l’empreinte avec vous</span>
        </dd>
      </dl>
    </Section>
  )
}

export function Configuration({ now }: { now: number }) {
  const configuration = useConfiguration()
  const priority = usePriority()
  const directory = useDirectory()
  const v = configuration.data
  const label = (id: string) => boxLabel(directory.data, id)

  return (
    <>
      <p className="sous-titre">
        Reçue de l’organisation, signée par le responsable. Elle se modifie depuis l’interface du responsable ; ici, la box
        montre ce qu’elle applique.
      </p>
      <Etat pending={configuration.isPending} error={configuration.error} />
      {v && (
        <Section id="version" title={v.enabled && v.sequence > 0 ? `Configuration centrale n° ${v.sequence}` : 'Configuration locale'}>
          {!v.enabled && <p className="discret">Configuration centralisée désactivée : la box applique sa configuration locale.</p>}
          {v.enabled && v.sequence === 0 && <p className="discret">Aucune version reçue : la box applique sa configuration locale.</p>}
          {v.enabled && v.sequence > 0 && (
            <p className="discret">
              {v.appliedAt && <>Appliquée {ilYa(v.appliedAt, now)}, sans redémarrage</>}
              {v.issuedAt && <> · publiée {quand(v.issuedAt, now)}</>}
              {v.author && <> par {v.author}</>}
              {v.comment && <> · motif « {v.comment} »</>}
              {' · '}groupes : {v.groups.length > 0 ? v.groups.join(', ') : 'aucun'}
            </p>
          )}
          {v.enabled && (
            <p className="petit">
              Priorité des séries : {v.priority ? 'règles centrales' : 'règles locales'} — routage vers les partenaires :{' '}
              {v.routing ? 'règles centrales' : 'règles locales'} — appareils et routes DICOM :{' '}
              {v.devices ? 'configuration centrale' : 'configuration locale'}.
            </p>
          )}
          {v.refused > 0 && v.refusal && (
            <p className="message-etat message-warn">
              Version n° {v.refused} refusée, la configuration en vigueur reste appliquée : {v.refusal}
            </p>
          )}
          {v.error && <p className="erreur petit">Dernière lecture impossible : {v.error}</p>}
          {v.checkedAt && <p className="discret petit">Dernière lecture de l’annuaire {ilYa(v.checkedAt, now)}.</p>}
        </Section>
      )}

      {v?.devices && <Appareils devices={v.devices} now={now} />}
      {v?.devices && (
        <Routes id="routes-envois" title="Envois : d’un appareil vers des box" routes={v.devices.outgoing} from={(x) => x} to={label} />
      )}
      {v?.devices && (
        <Routes id="routes-receptions" title="Réceptions : d’une box vers des PACS" routes={v.devices.incoming} from={label} to={(x) => x} />
      )}
      {v && v.routingRules.length > 0 && (
        <Section id="routage" title="Routage vers les partenaires">
          <ul className="regles">
            {v.routingRules.map((r) => (
              <li key={r} className="regle mono">{r}</li>
            ))}
          </ul>
        </Section>
      )}

      <Section
        id="priorites"
        title="Priorité des séries"
        intro={
          priority.data &&
          `La première règle qui correspond fixe la priorité (1 passe en premier). Sinon : priorité ${priority.data.defaultPriority}. Source : ${priority.data.source}.`
        }
      >
        <Etat pending={priority.isPending} error={priority.error} />
        {priority.data?.error && (
          <p className="erreur petit">Dernières règles refusées, les précédentes restent en vigueur : {priority.data.error}</p>
        )}
        {priority.data && priority.data.rules.length === 0 && (
          <p className="discret">Aucune règle : les séries partent dans l’ordre d’arrivée.</p>
        )}
        {priority.data && priority.data.rules.length > 0 && (
          <ol className="regles" aria-label="Règles de priorité des séries">
            {priority.data.rules.map((r, i) => (
              <li key={r.name} className="regle">
                <span className="regle-rang mono" aria-hidden="true">{i + 1}</span>
                <span className="regle-nom">{r.name}</span>
                <span className="etiquette etiquette-filtre mono">{conditions(r)}</span>
                <span className="fleche" aria-hidden="true">→</span>
                <span className="etiquette etiquette-cible fort">priorité {r.priority}</span>
              </li>
            ))}
          </ol>
        )}
      </Section>

      {directory.data?.enabled && directory.data.boxId && <Identite d={directory.data} />}
    </>
  )
}
