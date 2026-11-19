import { useState } from 'react'
import { ENROLLMENT_REQUEST_URL, type DirectoryView } from '../api/directory'
import { Pastille, Section, type Tone } from '../components/ui'
import { ilYa, quand } from '../format'

interface EtapeMes {
  title: string
  detail: string
  state: 'ok' | 'progress' | 'fail' | 'todo'
  label: string
}

const TONE: Record<EtapeMes['state'], Tone> = { ok: 'ok', progress: 'progress', fail: 'fail', todo: 'neutral' }

function etapes(d: DirectoryView, now: number): EtapeMes[] {
  const s = d.setup
  const envoyee = !!s?.submittedAt
  let demande: EtapeMes
  if (envoyee) {
    demande = {
      title: 'Demande d’enrôlement envoyée', state: 'ok', label: 'fait',
      detail: `Déposée dans l’annuaire ${quand(s!.submittedAt!, now)}, avec la preuve du code d’enrôlement.`,
    }
  } else if (s?.error) {
    demande = {
      title: 'Demande d’enrôlement envoyée', state: 'fail', label: 'en échec',
      detail: `${s.error}${s.attemptAt ? ` (essai ${quand(s.attemptAt, now)})` : ''}.`,
    }
  } else if (s?.codeProvided) {
    demande = {
      title: 'Demande d’enrôlement envoyée', state: 'progress', label: 'en cours',
      detail: 'La box dépose sa demande dans l’annuaire avec le code d’enrôlement fourni à l’installation.',
    }
  } else {
    demande = {
      title: 'Demande d’enrôlement remise', state: 'progress', label: 'à faire',
      detail: 'Pas de code d’enrôlement : téléchargez la demande ci-dessous et remettez-la au responsable.',
    }
  }
  return [
    { title: 'Identité de la box créée', state: 'ok', label: 'fait', detail: 'Clés de la box générées sur ce poste ; la clé privée ne le quitte pas.' },
    {
      title: 'Clé de l’organisation installée', state: s ? 'ok' : 'todo', label: s ? 'fait' : '',
      detail: s
        ? `Empreinte vérifiée au démarrage : la box ne fera confiance qu’à ${s.organization}.`
        : 'La clé publique de l’organisation n’est pas encore installée.',
    },
    demande,
    {
      title: 'Validation par le responsable', state: envoyee ? 'progress' : 'todo', label: envoyee ? 'en attente' : '',
      detail: 'Le responsable voit la demande dans son interface. Lisez-lui l’empreinte ci-dessous.',
    },
    { title: 'Box active', state: 'todo', label: '', detail: 'La box lit son entrée signée dans l’annuaire, puis sa configuration centrale.' },
  ]
}

export function MiseEnService({ d, now }: { d: DirectoryView; now: number }) {
  const [copie, setCopie] = useState<'' | 'ok' | 'ko'>('')
  const s = d.setup
  const sync = d.lastSync
  const copier = () => {
    navigator.clipboard
      ?.writeText(d.shortCode ?? '')
      .then(() => setCopie('ok'), () => setCopie('ko'))
  }
  return (
    <>
      <p className="sous-titre">
        La box attend d’être validée par le responsable de l’organisation. Cette page se met à jour toute seule.
      </p>
      <Section id="etapes" title="Étapes">
        <ol className="etapes-mes">
          {etapes(d, now).map((e, i) => (
            <li key={e.title} className={`etape-mes etape-mes-${e.state}`}>
              <span className="etape-numero" aria-hidden="true">{e.state === 'ok' ? '✓' : i + 1}</span>
              <div>
                <div className="pastilles">
                  <span className="etape-titre">{e.title}</span>
                  {e.label ? <Pastille tone={TONE[e.state]}>{e.label}</Pastille> : <span className="visually-hidden">(à venir)</span>}
                </div>
                <div className="discret">{e.detail}</div>
              </div>
            </li>
          ))}
        </ol>
      </Section>

      <section className="carte carte-empreinte" aria-labelledby="empreinte">
        <h2 id="empreinte">À lire au responsable</h2>
        <p className="discret">
          Par téléphone ou de vive voix : il compare cette empreinte à celle de la demande qu’il valide. Si elles diffèrent,
          il refuse la demande.
        </p>
        <div className="pastilles">
          <span className="empreinte mono">{d.shortCode}</span>
          <button type="button" className="bouton-secondaire" onClick={copier}>
            {copie === 'ok' ? 'Copiée' : 'Copier l’empreinte'}
          </button>
          <span role="status" className="visually-hidden">{copie === 'ok' ? 'Empreinte copiée' : copie === 'ko' ? 'Copie impossible' : ''}</span>
        </div>
        <p>
          <a href={ENROLLMENT_REQUEST_URL} download>Télécharger la demande d’enrôlement</a>
        </p>
      </section>

      <Section id="liaisons" title="Liaisons">
        <dl className="liste-definitions">
          <dt>Annuaire</dt>
          <dd>
            {s?.directoryUrl ?? '—'}
            {sync?.at && !sync.error && <> · <span className="texte-ok">joignable</span>, dernière lecture {ilYa(sync.at, now)}</>}
            {sync?.error && <> · <span className="erreur">injoignable</span> : {sync.error}</>}
            {!sync?.at && !sync?.error && <> · pas encore lu</>}
          </dd>
          <dt>Organisation</dt>
          <dd>
            {s ? (
              <>
                {s.organization} · empreinte <span className="mono">{s.organizationShortCode}</span>, vérifiée au démarrage
              </>
            ) : (
              '—'
            )}
          </dd>
          <dt>Code d’enrôlement</dt>
          <dd>{s?.codeProvided ? 'fourni à l’installation' : 'non fourni : demande à remettre au responsable'}</dd>
          <dt>Identifiant de la box</dt>
          <dd className="mono">{d.boxId}</dd>
        </dl>
      </Section>
    </>
  )
}
