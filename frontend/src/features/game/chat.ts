import type { GamePresentation } from './types'

export type ChatChannel = 'PUBLIC' | 'PRIVATE' | 'WOLF'
export interface ChatItem {
  id: string
  kind: 'speech' | 'system'
  content: string
  seatNo?: number
  round?: number
  order?: number
  streaming?: boolean
}
export function chatChannels(view: GamePresentation): ChatChannel[] {
  const result: ChatChannel[] = ['PUBLIC']
  if (view.privateRecords.some(record => record.scope === 'PRIVATE') || view.privateResult) result.push('PRIVATE')
  if (view.wolfMessages.length || view.privateRecords.some(record => record.scope === 'WOLF') || view.permissions.some(grant => grant.type === 'WOLF_CHAT')) result.push('WOLF')
  return result
}
export function chatItems(view: GamePresentation, channel: ChatChannel): ChatItem[] {
  if (!chatChannels(view).includes(channel)) return []
  const messages = channel === 'PUBLIC' ? view.messages : channel === 'WOLF' ? view.wolfMessages : []
  const records = channel === 'PUBLIC' ? view.records : view.privateRecords.filter(record => record.scope === channel)
  const items: ChatItem[] = records.filter(record => !messages.some(message => message.id === record.id)).map(record => ({ id: `${channel}:record:${record.id}`, kind: 'system', content: record.text, round: record.round, order: record.order }))
  items.push(...messages.map(message => ({ id: `${channel}:speech:${message.id}`, kind: 'speech' as const, content: message.content, seatNo: message.seatNo, round: message.round, order: message.order })))
  items.sort((a, b) => (a.order ?? (a.kind === 'system' ? -1 : Number.MAX_SAFE_INTEGER)) - (b.order ?? (b.kind === 'system' ? -1 : Number.MAX_SAFE_INTEGER)))
  if (channel === 'PRIVATE' && view.privateResult && !records.some(record => record.text.includes(view.privateResult!.content))) items.push({ id: `PRIVATE:result:${view.privateResult.content}`, kind: 'system', content: view.privateResult.content })
  if (channel === 'PUBLIC' && view.preview && !messages.some(message => message.id === view.preview!.speechId)) items.push({ id: `PUBLIC:speech:${view.preview.speechId}`, kind: 'speech', content: view.preview.content, seatNo: view.preview.seatNo, round: view.round, streaming: true })
  return items
}
