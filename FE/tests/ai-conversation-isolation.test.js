const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const path = require('node:path')

function setup() {
  const requests = [], ui = { loading: 0, hidden: 0, toasts: [] }
  let definition
  const app = { globalData: { userId: 'u1', openid: 'synthetic' }, onLoginReady() {} }
  const settings = { historyEnabled: true, motionEnabled: false }
  const context = {
    getApp: () => app, Page: value => { definition = value }, console,
    setTimeout: fn => fn(),
    wx: { showLoading: () => ui.loading++, hideLoading: () => ui.hidden++,
      showToast: value => ui.toasts.push(value),
      getSystemInfoSync: () => ({ windowHeight: 700, windowWidth: 375, screenHeight: 700 }) },
    require: name => name.endsWith('/settings') ? { loadSettings: () => ({ ...settings }) } : {
      request: payload => new Promise((resolve, reject) => requests.push({ payload, resolve, reject }))
    }
  }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/ai/ai.js'), 'utf8'), context)
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)),
    setData(value, callback) { Object.assign(this.data, value); if (callback) callback() } }
  page.data.userId = 'u1'; page.invalidateView()
  return { page, requests, ui, settings, app }
}
const flush = () => new Promise(resolve => setImmediate(resolve))
const message = () => ({ id: 'question', text: '问题', manualRef: null })

test('A late answer cannot bind its ID or append into a new view', async () => {
  const { page, requests } = setup()
  page.requestAiReply(message()); page.onClearChatTap()
  requests[0].resolve({ conversationId: 'A', answerText: 'A answer' }); await flush()
  assert.equal(page.data.currentConversationId, '')
  assert.equal(page.data.messages.length, 1)
})
test('A/B detail completion in reverse order preserves B and its device', async () => {
  const { page, requests } = setup()
  page.loadConversationDetail('A', false); page.loadConversationDetail('B', false)
  requests[1].resolve({ conversation: { id: 'B', manualId: 'mb', manualName: '设备B' }, messages: [] }); await flush()
  requests[0].resolve({ conversation: { id: 'A', manualId: 'ma', manualName: '设备A' }, messages: [] }); await flush()
  assert.equal(page.data.currentConversationId, 'B'); assert.equal(page.data.selectedManual.name, '设备B')
})
test('old catch and finally cannot show an error or hide the new loading owner', async () => {
  const { page, requests, ui } = setup()
  page.requestAiReply(message()); page.onClearChatTap(); page.requestAiReply(message())
  const hides = ui.hidden
  requests[0].reject(new Error('old failure')); await flush()
  assert.equal(ui.hidden, hides); assert.equal(ui.toasts.length, 0)
  requests[1].resolve({ conversationId: 'B', answerText: 'B' }); await flush()
  assert.equal(ui.hidden, hides + 1); assert.equal(page.data.messages.at(-1).text, 'B')
})
test('old automatic list restore cannot revive a cleared view', async () => {
  const { page, requests } = setup()
  page.loadConversationList(true); page.onClearChatTap()
  requests[0].resolve([{ id: 'A', title: 'old' }]); await flush()
  assert.equal(requests.length, 1); assert.equal(page.data.currentConversationId, '')
})
test('history disabled keeps identity but sends no conversation and is invalidated on toggle', async () => {
  const { page, requests, settings } = setup()
  page.data.currentConversationId = 'A'; settings.historyEnabled = false; page.refreshAppSettings()
  page.requestAiReply(message())
  assert.equal(requests[0].payload.data.userId, 'u1')
  assert.equal(requests[0].payload.data.persistHistory, false)
  assert.equal(requests[0].payload.data.conversationId, '')
  settings.historyEnabled = true; page.refreshAppSettings()
  requests[0].resolve({ answerText: 'late' }); await flush()
  assert.equal(page.data.messages.length, 1)
})
test('identity change rejects old callbacks without a login handler being needed', async () => {
  const { page, requests } = setup()
  page.requestAiReply(message()); page.data.userId = 'u2'
  requests[0].resolve({ conversationId: 'A', answerText: 'private' }); await flush()
  assert.equal(page.data.currentConversationId, '')
})
test('unload rejects every outstanding callback', async () => {
  const { page, requests } = setup()
  page.requestAiReply(message()); page.onUnload()
  requests[0].resolve({ answerText: 'late' }); await flush()
  assert.equal(page.data.messages.length, 1)
})
test('an explicit retry of the same message reuses its requestId; no automatic retry occurs', async () => {
  const { page, requests } = setup(); const msg = message()
  page.requestAiReply(msg); requests[0].reject(new Error('unknown')); await flush()
  assert.equal(requests.length, 1)
  page.requestAiReply(msg)
  assert.equal(requests[0].payload.data.requestId, requests[1].payload.data.requestId)
})
test('deleting A cannot clear B after switching while the delete is pending', async () => {
  const { page, requests } = setup()
  page.data.currentConversationId = 'A'; page.deleteConversation('A'); page.loadConversationDetail('B', true)
  requests[1].resolve({ conversation: { id: 'B' }, messages: [{ id: 'b', role: 'ai', text: 'B' }] }); await flush()
  requests[0].resolve({}); await flush()
  assert.equal(page.data.currentConversationId, 'B'); assert.equal(page.data.messages[0].text, 'B')
})
test('login state changed while page was hidden invalidates old answer on show', async () => {
  const { page, requests, app } = setup()
  page.requestAiReply(message()); app.globalData.userId = 'u2'; page.onShow()
  requests[0].resolve({ answerText: 'private u1', conversationId: 'A' }); await flush()
  assert.equal(page.data.userId, 'u2'); assert.equal(page.data.messages.length, 1)
})
test('retrying an old message after an identity or view switch is rejected', () => {
  const { page, requests, ui } = setup(); const msg = message()
  page.requestAiReply(msg); page.onClearChatTap(); page.requestAiReply(msg)
  assert.equal(requests.length, 1); assert.equal(ui.toasts.at(-1).title, '原请求所属视图已切换，请先查看历史')
})
