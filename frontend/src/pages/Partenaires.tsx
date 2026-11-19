import { BOX_STATUS_LABELS, type DirectoryBox, type Freshness, type PartnerOrganization } from '../api/directory'
import type { Peer } from '../api/transfers'
import { PairCell } from '../components/PairCell'
import { Etat, Pastille, Point, Section, type Tone } from '../components/ui'
import { useDirectory, usePeers } from '../data'
import { formatDay, ilYa, quand } from '../format'

function etatPartenaire(p: Peer, directoryEnabled: boolean): { tone: Tone; label: string } {
  if (!directoryEnabled) return { tone: 'neutral', label: 'appairé hors annuaire' }
  if (p.boxId === null) return { tone: 'warn', label: 'non vérifié par l’annuaire' }
  if (p.directoryStatus === 'ACTIVE') return { tone: 'ok', label: 'vérifié par l’annuaire' }
  return { tone: 'fail', label: p.directoryStatus ? BOX_STATUS_LABELS[p.directoryStatus] : 'absent de l’annuaire' }
}

function fraicheur(f: Freshness | null, now: number): { tone: Tone; text: string } {
  if (!f) return { tone: 'neutral', text: 'fraîcheur non contrôlée' }
  if (!f.issuedAt) return { tone: 'warn', text: f.error ?? 'pas encore de preuve de fraîcheur' }
  if (f.stale) return { tone: 'fail', text: `preuve de fraîcheur trop ancienne (${quand(f.issuedAt, now)}) : échanges arrêtés` }
  return { tone: f.error ? 'warn' : 'ok', text: `preuve de fraîcheur ${ilYa(f.issuedAt, now)}${f.error ? ` (${f.error})` : ''}` }
}

const ACCORD: Record<PartnerOrganization['status'], string> = {
  ACTIVE: 'accord en vigueur',
  REVOKED: 'accord révoqué',
  EXPIRED: 'accord expiré',
  NOT_YET_VALID: 'accord pas encore en vigueur',
}

const perimetre = (g: string[]) => (g.length === 0 ? 'aucun' : g.includes('*') ? 'tous' : g.join(', '))

function Organisation({ p, now }: { p: PartnerOrganization; now: number }) {
  const ok = p.status === 'ACTIVE'
  const f = fraicheur(p.freshness, now)
  return (
    <div className={`carte-element${ok ? '' : ' carte-echec'}`}>
      <div className="ligne-titre">
        <h3>{p.organization}</h3>
        <Pastille tone={ok ? 'ok' : 'fail'}>{ACCORD[p.status]}</Pastille>
      </div>
      <span className="mono discret">{p.shortCode} · jusqu’au {formatDay(p.validUntil)}</span>
      <span className="discret">
        Périmètre : groupes locaux {perimetre(p.localGroups)} ; groupes du partenaire {perimetre(p.partnerGroups)}
      </span>
      <span className="discret">Relais : {p.relays.join(', ') || '—'}</span>
      <span className="discret">
        {!p.lastSync
          ? 'Annuaire pas encore lu'
          : p.lastSync.error
            ? <span className="erreur">{p.lastSync.error}</span>
            : p.lastSync.at && `Annuaire lu ${ilYa(p.lastSync.at, now)} : ${p.boxes} box utilisable${p.boxes > 1 ? 's' : ''}`}
      </span>
      <Pastille tone={f.tone}>{f.text}</Pastille>
    </div>
  )
}

function Protocole({ b }: { b: DirectoryBox }) {
  // Une seule version (iu1.0 partout) : rien à signaler.
  if (!b.protocols || b.protocols.length <= 1) return null
  return (
    <div className="sous-ligne" title="Versions du protocole annoncées par la box">
      protocole {b.protocol ?? 'aucun en commun'} ({b.protocols.join(', ')})
    </div>
  )
}

export function Partenaires({ now }: { now: number }) {
  const peers = usePeers()
  const directory = useDirectory()
  const d = directory.data
  const enabled = !!d?.enabled
  const f = d ? fraicheur(d.freshness, now) : null

  return (
    <>
      <p className="sous-titre">Les box avec lesquelles celle-ci échange, et celles que l’annuaire lui permet d’appairer.</p>

      <Section
        id="appaires"
        title="Partenaires appairés"
        intro={enabled ? 'Revérifiés à chaque lecture de l’annuaire : une box révoquée ou hors accord n’échange plus.' : undefined}
      >
        <Etat pending={peers.isPending} error={peers.error} />
        {peers.data?.length === 0 && <p className="discret">Aucun partenaire appairé.</p>}
        <ul className="grille-cartes">
          {peers.data?.map((p) => {
            const e = etatPartenaire(p, enabled)
            return (
              <li key={p.name} className={`carte-element${e.tone === 'fail' ? ' carte-echec' : ''}`}>
                <div className="ligne-titre">
                  <h3>{p.displayName ?? p.name}</h3>
                </div>
                <span className="mono discret">
                  contact « {p.name} » · appairé le {formatDay(p.pairedAt)}
                </span>
                <Pastille tone={e.tone}>{e.label}</Pastille>
              </li>
            )
          })}
        </ul>
      </Section>

      {directory.isError && <Etat pending={false} error={directory.error} />}
      {d && !d.enabled && (
        <Section id="annuaire" title="Box de l’annuaire">
          <p className="discret">Annuaire non activé : tout partenaire appairé est accepté.</p>
        </Section>
      )}
      {d && d.enabled && (
        <Section
          id="annuaire"
          title="Box de l’annuaire"
          aside={f && <span className="etat-ligne"><Point tone={f.tone} />{f.text}</span>}
        >
          {d.boxes.length === 0 ? (
            <p className="discret">Aucune box lue dans l’annuaire.</p>
          ) : (
            <div className="table-scroll">
              <table className="liste" aria-label="Box de l’annuaire">
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
                        <strong>{b.displayName}</strong>
                        <div className="sous-ligne">{b.organization ? `organisation partenaire : ${b.organization}` : 'cette organisation'}</div>
                        <Protocole b={b} />
                      </td>
                      <td>
                        <Pastille tone={b.status === 'ACTIVE' ? 'ok' : 'fail'}>{BOX_STATUS_LABELS[b.status]}</Pastille>
                        {b.status === 'ACTIVE' && !b.encrypted && (
                          <div className="erreur petit">sans clés de chiffrement : entrée à réémettre</div>
                        )}
                      </td>
                      <td className="discret">{formatDay(b.validUntil)}</td>
                      <td>
                        <PairCell box={b} own={b.boxId === d.boxId} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {d.lastSync?.rejected ? (
            <p className="erreur petit">{d.lastSync.rejected} entrée(s) écartée(s) à la dernière lecture.</p>
          ) : null}
        </Section>
      )}

      {d && d.partners.length > 0 && (
        <Section id="orgs" title="Organisations partenaires">
          <div className="grille-cartes large">
            {d.partners.map((p) => (
              <Organisation key={p.shortCode} p={p} now={now} />
            ))}
          </div>
        </Section>
      )}
    </>
  )
}
