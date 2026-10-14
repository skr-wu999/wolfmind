export interface RuleSettings {
  winCondition?: string
  witchSelfSave?: boolean
  witchSelfSaveFirstNightOnly?: boolean
}

export const demoRules: RuleSettings = {
  winCondition: 'SIDE_KILL', witchSelfSave: true, witchSelfSaveFirstNightOnly: true,
}
