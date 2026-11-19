import type { Overview } from '../api/transfers'
import { OUTBOUND_LABELS } from '../api/transfers'
import { Etat, Pastille, Point, Section } from '../components/ui'
import { useConfiguration, useDirectory, useInbound, useOverview, useTransfers } from '../data'
import { aSurveiller, inLabel, inTone, outTone } from '../etat'
import { ilYa, shortUid } from '../format'

const sum = (counts: Partial<Record<string, number>>, keys: string[]) => keys.reduce((s, k) => s + (counts[k] ?? 0), 0)

function Indicateurs({ o }: { o: Overview }) {
  const failedOut = sum(o.outbound, ['FAILED'])
  const failedIn = sum(o.inbound, ['FAILED'])
  const tiles: { label: string; value: number; note: string; tone?: 'warn' | 'fail' | 'ok' }[] = [
    {
      label: 'Envois en cours', value: sum(o.outbound, ['RECEIVED', 'PACKAGED', 'UPLOADED', 'ANNOUNCED']),
      note: o.ackOverdue > 0 ? `${o.ackOverdue} accusé${o.ackOverdue > 1 ? 's' : ''} en retard` : 'aucun accusé en retard',
      tone: o.ackOverdue > 0 ? 'warn' : undefined,
    },
    { label: 'Envois confirmés', value: sum(o.outbound, ['ACKNOWLEDGED']), note: 'depuis la mise en service' },
    { label: 'Réceptions restituées', value: sum(o.inbound, ['RESTORED']), note: 'depuis la mise en service', tone: 'ok' },
    {
      label: 'Échecs', value: failedOut + failedIn,
      note: `${failedOut} envoi${failedOut > 1 ? 's' : ''}, ${failedIn} réception${failedIn > 1 ? 's' : ''}`,
      tone: failedOut + failedIn > 0 ? 'fail' : undefined,
    },
  ]
  return (
    <ul className="indicateurs" aria-label="Indicateurs">
      {tiles.map((t) => (
        <li key={t.label} className="indicateur">
          <span className="indicateur-label">{t.label}</span>
          <span className="indicateur-valeur">{t.value}</span>
          <span className={`indicateur-note${t.tone ? ` note-${t.tone}` : ''}`}>{t.note}</span>
        </li>
      ))}
      <li className="indicateur">
        <span className="indicateur-label">Partenaires</span>
        <span className="indicateur-valeur">{o.peers}</span>
        <span className="indicateur-note">box appairées</span>
      </li>
    </ul>
  )
}

export function Activite({ now }: { now: number }) {
  const overview = useOverview()
  const out = useTransfers()
  const inb = useInbound()
  const conf = useConfiguration()
  const dir = useDirectory()
  const points = aSurveiller(out.data, inb.data, conf.data, dir.data, now)
  // « Tout fonctionne » ne se dit qu'avec toutes les informations en main.
  const requetes = [overview, out, inb, conf, dir]
  const incomplet = requetes.some((q) => !q.isSuccess || q.isError)

  return (
    <>
      <p className="sous-titre">
        {points.length > 0
          ? `${points.length} point${points.length > 1 ? 's' : ''} à surveiller.${incomplet ? '' : ' Le reste fonctionne.'}`
          : incomplet
            ? requetes.some((q) => q.isError)
              ? 'Une partie des informations est indisponible : impossible de dire si tout fonctionne.'
              : 'Chargement…'
            : 'Rien à signaler : tout fonctionne.'}
      </p>

      {points.length > 0 && (
        <Section id="surveiller" title="À surveiller">
          <ul className="surveiller">
            {points.map((p) => (
              <li key={p.key} className="point-surveiller">
                <Point tone={p.tone} large />
                <div className="point-texte">
                  <span className="point-titre">{p.title}</span>
                  <span className="discret">{p.detail}</span>
                </div>
                <a className="bouton-lien" href={p.href}>
                  {p.action}
                </a>
              </li>
            ))}
          </ul>
        </Section>
      )}

      {overview.data ? <Indicateurs o={overview.data} /> : <Etat pending={overview.isPending} error={overview.error} />}

      <div className="deux-colonnes">
        <Section id="derniers-envois" title="Derniers envois" aside={<a href="#envois">Tous les envois →</a>}>
          <Etat pending={out.isPending} error={out.error} />
          {out.data?.length === 0 && <p className="discret">Aucun envoi pour l’instant.</p>}
          <ul className="recents">
            {out.data?.slice(0, 5).map((t) => (
              <li key={t.id}>
                <span className="mono" title={t.studyInstanceUid}>{shortUid(t.studyInstanceUid, 16)}</span>
                <span className="recent-qui">→ {t.destination}</span>
                <Pastille tone={outTone(t)}>{OUTBOUND_LABELS[t.state]}</Pastille>
                <span className="recent-quand">{ilYa(t.updatedAt, now)}</span>
              </li>
            ))}
          </ul>
        </Section>
        <Section id="dernieres-receptions" title="Dernières réceptions" aside={<a href="#receptions">Toutes les réceptions →</a>}>
          <Etat pending={inb.isPending} error={inb.error} />
          {inb.data?.length === 0 && <p className="discret">Aucune réception pour l’instant.</p>}
          <ul className="recents">
            {inb.data?.slice(0, 5).map((t) => (
              <li key={`${t.peerName}/${t.transferId}`}>
                <span className="mono" title={t.studyInstanceUid}>{shortUid(t.studyInstanceUid, 16)}</span>
                <span className="recent-qui">← {t.peerName}</span>
                <Pastille tone={inTone(t)}>{inLabel(t)}</Pastille>
                <span className="recent-quand">{ilYa(t.updatedAt, now)}</span>
              </li>
            ))}
          </ul>
        </Section>
      </div>
    </>
  )
}
