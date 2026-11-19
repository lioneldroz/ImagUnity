const sizeFormat = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 })
const dateFormat = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short', timeStyle: 'medium' })

/** Taille lisible : « 50,8 Ko », « 1,2 Go ». */
export function formatSize(bytes: number): string {
  const units = ['o', 'Ko', 'Mo', 'Go', 'To']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${sizeFormat.format(value)} ${units[unit]}`
}

export function formatDate(iso: string): string {
  return dateFormat.format(new Date(iso))
}

/** UID long abrégé pour les tableaux : le début et la fin restent lisibles. */
export function shortUid(uid: string, max = 28): string {
  return uid.length <= max ? uid : `${uid.slice(0, 12)}…${uid.slice(-(max - 13))}`
}

/** Temps écoulé : « à l'instant », « il y a 3 min », « il y a 2 h », « il y a 4 j ». */
export function ilYa(iso: string, now = Date.now()): string {
  const s = Math.max(0, (now - new Date(iso).getTime()) / 1000)
  if (s < 60) return 'à l’instant'
  if (s < 3600) return `il y a ${Math.floor(s / 60)} min`
  if (s < 86400) return `il y a ${Math.floor(s / 3600)} h`
  return `il y a ${Math.floor(s / 86400)} j`
}

const heure = new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit' })
const jourMois = new Intl.DateTimeFormat('fr-FR', { day: '2-digit', month: '2-digit' })

/** Date en clair : « il y a 3 min » dans l'heure, sinon « aujourd'hui, 14:02 », « hier, 18:40 », « 12/11, 09:15 ». */
export function quand(iso: string, now = Date.now()): string {
  const d = new Date(iso)
  if (now - d.getTime() < 3_600_000) return ilYa(iso, now)
  const jour = (x: Date) => x.toDateString()
  if (jour(d) === jour(new Date(now))) return `aujourd’hui, ${heure.format(d)}`
  if (jour(d) === jour(new Date(now - 86_400_000))) return `hier, ${heure.format(d)}`
  return `${jourMois.format(d)}, ${heure.format(d)}`
}

/** Délai avant une date à venir : « dans 27 min », « dans 2 h », « maintenant ». */
export function dans(iso: string, now = Date.now()): string {
  const s = (new Date(iso).getTime() - now) / 1000
  if (s < 60) return 'maintenant'
  if (s < 3600) return `dans ${Math.ceil(s / 60)} min`
  return `dans ${Math.round(s / 3600)} h`
}

const dayFormat = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short' })

/** Date seule : « 19/11/2026 ». */
export function formatDay(iso: string): string {
  return dayFormat.format(new Date(iso))
}
