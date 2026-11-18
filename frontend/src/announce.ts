/**
 * Annonce un message aux lecteurs d'écran (RGAA 7.5), par la région de statut #annonces de la page.
 *
 * La région reste toujours présente (une région masquée ou créée avec son texte n'est pas toujours annoncée) ; elle est
 * vidée puis remplie, pour qu'un même message répété soit annoncé de nouveau.
 */
let timer: ReturnType<typeof setTimeout> | undefined

export function announce(text: string): void {
  const region = document.getElementById('annonces')
  if (!region) {
    return
  }
  clearTimeout(timer)
  region.textContent = ''
  timer = setTimeout(() => {
    region.textContent = text
  }, 50)
}
