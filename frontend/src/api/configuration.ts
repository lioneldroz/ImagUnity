import { getJson } from './http'

/** GET /api/v1/configuration : configuration centralisée appliquée par la box. */
export interface CentralConfigurationView {
  /** Faux : la box garde sa configuration locale (annuaire désactivé ou verrouillage local). */
  enabled: boolean
  /** Version appliquée, 0 : configuration locale. */
  sequence: number
  issuedAt: string | null
  author: string | null
  comment: string | null
  appliedAt: string | null
  /** La version fixe les règles de priorité / de routage (sinon : règles locales). */
  priority: boolean
  routing: boolean
  /** Règles de routage centrales : « nom → partenaires ». */
  routingRules: string[]
  /** Dernière version refusée, 0 : aucune. */
  refused: number
  refusal: string | null
  checkedAt: string | null
  error: string | null
}

export const fetchCentralConfiguration = () => getJson<CentralConfigurationView>('/api/v1/configuration')
