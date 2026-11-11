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
