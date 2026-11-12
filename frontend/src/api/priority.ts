import { getJson } from './http'

/** Une règle de priorité : 1 = la plus haute, 9 = la plus basse. */
export interface PriorityRuleView {
  name: string
  priority: number
  /** Champs qui doivent tous correspondre : mot-clé DICOM → motif. */
  and: Record<string, string>
  /** Champs dont un seul suffit. */
  or: Record<string, string>
}

/** GET /api/v1/priority. */
export interface PriorityView {
  source: string
  loadedAt: string | null
  /** Dernier fichier de règles refusé (les règles précédentes restent en vigueur). */
  error: string | null
  defaultPriority: number
  rules: PriorityRuleView[]
}

export const fetchPriority = () => getJson<PriorityView>('/api/v1/priority')
