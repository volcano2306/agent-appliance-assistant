const app = getApp()
const { request } = require('../../utils/request')
const { loadSettings } = require('../../utils/settings')

function buildDefaultAiMessage() {
  return {
    id: 'welcome',
    role: 'ai',
    text: '你好，我是智能家电助手。请输入问题，我会结合当前说明书内容为你解答，并尽量标注引用页码。',
    manualRef: null,
    image: '',
    sources: [],
    structuredAnswer: null,
    insufficientContext: false
  }
}

function buildApiUrl(path) {
  const baseUrl = ((app.globalData && app.globalData.baseUrl) || '').replace(/\/$/, '')
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  return baseUrl + normalizedPath
}

function normalizeStructuredAnswer(answer) {
  if (!answer || typeof answer !== 'object') {
    return null
  }

  const summary = trimText(answer.summary)
  const steps = Array.isArray(answer.steps)
    ? answer.steps.map(item => trimText(item)).filter(Boolean)
    : []
  const cautions = Array.isArray(answer.cautions)
    ? answer.cautions.map(item => trimText(item)).filter(Boolean)
    : []

  if (!summary && !steps.length && !cautions.length) {
    return null
  }

  return {
    summary,
    steps,
    cautions
  }
}

function normalizeSources(sources) {
  if (!Array.isArray(sources)) {
    return []
  }

  return sources
    .filter(item => item && (item.fragmentId || item.snippet || item.title || item.url))
    .map((item, index) => ({
      kind: item.kind || (item.url ? 'web_result' : 'manual_fragment'),
      fragmentId: item.fragmentId || item.url || `source-${index}`,
      page: item.page || 0,
      pageLabel: item.kind === 'web_result' || item.url
        ? '网页来源'
        : (item.page ? `第 ${item.page} 页` : '说明书片段'),
      title: trimText(item.title),
      url: trimText(item.url),
      snippet: (item.snippet || '').trim(),
      sourceType: item.sourceType || ''
    }))
}

function inferManualType(refId, manualId, manualType) {
  if (manualType === 'mine' || manualType === 'platform') {
    return manualType
  }
  if (refId && manualId && refId !== manualId) {
    return 'mine'
  }
  return 'platform'
}

function trimText(value) {
  return typeof value === 'string' ? value.trim() : ''
}

function formatConversationTime(value) {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }

  const now = new Date()
  const sameYear = now.getFullYear() === date.getFullYear()
  const sameMonth = now.getMonth() === date.getMonth()
  const sameDate = now.getDate() === date.getDate()
  const hour = String(date.getHours()).padStart(2, '0')
  const minute = String(date.getMinutes()).padStart(2, '0')

  if (sameYear && sameMonth && sameDate) {
    return `${hour}:${minute}`
  }

  const month = date.getMonth() + 1
  const day = date.getDate()
  return sameYear ? `${month}/${day}` : `${date.getFullYear()}/${month}/${day}`
}

function resolveConversationTimestamp(value) {
  if (!value) {
    return 0
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? 0 : date.getTime()
}

function buildConversationTags(item, selectedManual) {
  const tags = []
  const manualName = trimText(item.manualName)
  const manualType = trimText(item.manualType)
  const normalizedConversation = {
    manualRefId: trimText(item.manualRefId),
    manualId: trimText(item.manualId)
  }

  if (manualName) {
    tags.push({
      key: `manual-${manualName}`,
      label: manualName,
      tone: 'manual'
    })
  } else {
    tags.push({
      key: 'general',
      label: '通用问答',
      tone: 'neutral'
    })
  }

  if (manualType === 'mine') {
    tags.push({
      key: 'type-mine',
      label: '我的说明书',
      tone: 'brand'
    })
  } else if (manualType === 'platform') {
    tags.push({
      key: 'type-platform',
      label: '平台说明书',
      tone: 'brand'
    })
  }

  if (selectedManual && matchConversationToManual(normalizedConversation, selectedManual)) {
    tags.push({
      key: 'current-manual',
      label: '当前说明书',
      tone: 'highlight'
    })
  }

  return tags
}

function normalizeConversationList(list, selectedManual) {
  if (!Array.isArray(list)) {
    return []
  }

  return list
    .map((item, index) => {
      const updatedAt = item.updatedAt || item.createdAt || ''
      const preview = trimText(item.lastMessagePreview || item.preview)
      return {
        id: item.id || `conversation-${index}`,
        title: trimText(item.title) || '新对话',
        preview: preview || '暂无内容',
        manualName: trimText(item.manualName),
        manualType: trimText(item.manualType),
        manualRefId: trimText(item.manualRefId),
        manualId: trimText(item.manualId),
        updatedAt,
        updatedAtText: formatConversationTime(updatedAt),
        sortTime: resolveConversationTimestamp(updatedAt),
        messageCount: Number(item.messageCount || 0),
        tags: buildConversationTags(item, selectedManual)
      }
    })
    .sort((left, right) => {
      if (right.sortTime !== left.sortTime) {
        return right.sortTime - left.sortTime
      }
      return (right.messageCount || 0) - (left.messageCount || 0)
    })
}

function normalizeLoadedMessage(item, index) {
  const manualRef = item && item.manualRef ? {
    id: item.manualRef.id || '',
    manualId: item.manualRef.manualId || item.manualRef.id || '',
    name: item.manualRef.name || '未命名说明书',
    type: item.manualRef.type || ''
  } : null

  return {
    id: item.id || `msg-${index}`,
    role: item.role === 'user' ? 'user' : 'ai',
    text: item.text || '',
    image: item.image || '',
    manualRef,
    sources: normalizeSources(item.sources),
    structuredAnswer: normalizeStructuredAnswer(item.structuredAnswer),
    insufficientContext: !!(item && item.insufficientContext)
  }
}

function normalizeConversationMessages(messages) {
  if (!Array.isArray(messages) || messages.length === 0) {
    return [buildDefaultAiMessage()]
  }
  return messages.map((item, index) => normalizeLoadedMessage(item, index))
}

function buildSelectedManualFromConversation(conversation) {
  if (!conversation) {
    return null
  }

  const refId = trimText(conversation.manualRefId)
  const manualId = trimText(conversation.manualId)
  const manualName = trimText(conversation.manualName)
  const manualType = trimText(conversation.manualType)
  if (!refId && !manualId && !manualName) {
    return null
  }

  return {
    id: refId || manualId,
    manualId: manualId || refId,
    name: manualName || '未命名说明书',
    type: manualType
  }
}

function matchConversationToManual(conversation, manualRef) {
  if (!conversation || !manualRef) {
    return false
  }

  const candidates = [manualRef.id, manualRef.manualId].filter(Boolean)
  return candidates.some(id => id === conversation.manualRefId || id === conversation.manualId)
}

Page({
  data: {
    statusBarHeight: 44,
    navHeight: 88,
    sessionBarHeight: 0,
    safeAreaBottom: 0,
    pageReady: false,
    chatHeight: 520,
    inputAreaHeight: 110,
    inputText: '',
    pendingImage: '',
    selectedManual: null,
    scrollIntoView: '',
    asking: false,
    userId: '',
    currentConversationId: '',
    conversationList: [],
    historyVisible: false,
    historyLoading: false,
    renameVisible: false,
    renameSaving: false,
    renameConversationId: '',
    renameValue: '',
    recording: false,
    transcribing: false,
    voiceStatusText: '',
    appSettings: loadSettings(),
    messages: [buildDefaultAiMessage()]
  },

  onLoad(options) {
    this._unloaded = false
    this.invalidateView()
    const sysInfo = wx.getSystemInfoSync()
    const statusBarHeight = sysInfo.statusBarHeight || 44
    const navHeight = statusBarHeight + 44
    const sessionBarHeight = Math.round(92 / 750 * sysInfo.windowWidth)
    const safeAreaBottom = sysInfo.screenHeight - (sysInfo.safeArea ? sysInfo.safeArea.bottom : sysInfo.screenHeight)

    let selectedManual = null
    if (options && options.manual) {
      try {
        const parsed = JSON.parse(decodeURIComponent(options.manual))
        selectedManual = {
          id: parsed.id || '',
          manualId: parsed.manualId || parsed.id || '',
          name: parsed.name || '未命名说明书',
          type: parsed.type || ''
        }
      } catch (error) {
        selectedManual = null
      }
    }

    const inputAreaHeight = selectedManual ? 182 : 110
    const chatHeight = (sysInfo.windowHeight || 600) - navHeight - sessionBarHeight - Math.round(inputAreaHeight / 750 * sysInfo.windowWidth) - safeAreaBottom

    this.setData({
      statusBarHeight,
      navHeight,
      sessionBarHeight,
      safeAreaBottom,
      inputAreaHeight,
      chatHeight,
      selectedManual
    }, () => {
      this.scrollToBottom()
    })

    this.refreshAppSettings()
    this.triggerPageEnter()
    this.initRecorder()

    app.onLoginReady(userId => {
      if (this._unloaded) return
      if (userId !== this.data.userId) {
        this.invalidateView()
        this.setData({ currentConversationId: '', messages: [buildDefaultAiMessage()], conversationList: [] })
      }
      this.setData({ userId }, () => {
        if (this.data.appSettings.historyEnabled) {
          this.loadConversationList(true)
        }
      })
    })
  },

  onShow() {
    const currentUser = (app.globalData && app.globalData.userId) || ''
    if (currentUser !== this.data.userId) {
      this.invalidateView()
      this.setData({ userId: currentUser, currentConversationId: '', messages: [buildDefaultAiMessage()], conversationList: [] })
    }
    this.refreshAppSettings()
    this.triggerPageEnter()
  },

  onUnload() {
    this.invalidateView()
    this._unloaded = true
    if (this.recorderManager && this.data.recording) {
      this.recorderManager.stop()
    }
  },

  refreshAppSettings() {
    const appSettings = loadSettings()
    if (this.data.appSettings.historyEnabled !== appSettings.historyEnabled) {
      this.invalidateView()
      this.setData({ currentConversationId: '', messages: [buildDefaultAiMessage()] })
    }
    this.setData({ appSettings })
    if (!appSettings.historyEnabled) {
      this.setData({
        historyVisible: false,
        currentConversationId: '',
        conversationList: []
      })
    }
  },

  triggerPageEnter() {
    const settings = loadSettings()
    if (settings.motionEnabled === false) {
      this.setData({ pageReady: true })
      return
    }
    this.setData({ pageReady: false })
    setTimeout(() => {
      this.setData({ pageReady: true })
    }, 30)
  },

  updateChatHeight(nextSelectedManual) {
    const sysInfo = wx.getSystemInfoSync()
    const inputAreaHeight = nextSelectedManual ? 182 : 110
    const chatHeight = (sysInfo.windowHeight || 600)
      - this.data.navHeight
      - this.data.sessionBarHeight
      - Math.round(inputAreaHeight / 750 * sysInfo.windowWidth)
      - this.data.safeAreaBottom
    this.setData({ inputAreaHeight, chatHeight })
  },

  scrollToBottom() {
    const last = this.data.messages[this.data.messages.length - 1]
    if (!last) {
      return
    }
    this.setData({ scrollIntoView: `msg-${last.id}` })
  },

  onBackTap() {
    wx.navigateBack({
      fail: () => {
        wx.switchTab({ url: '/pages/index/index' })
      }
    })
  },

  onHistoryTap() {
    if (!this.data.appSettings.historyEnabled) {
      wx.showToast({ title: '聊天历史已在设置中关闭', icon: 'none' })
      return
    }
    this.setData({ historyVisible: true })
    if (!this.data.historyLoading && this.data.userId) {
      this.loadConversationList(false)
    }
  },

  onHistoryClose() {
    this.setData({ historyVisible: false })
  },

  onHistoryPanelTap() {},

  onClearChatTap() {
    // Clear the view, not the persistent conversation. DELETE is a separate action.
    this.invalidateView()
    this.setData({
      currentConversationId: '',
      historyVisible: false,
      messages: [buildDefaultAiMessage()],
      inputText: '',
      pendingImage: '',
      asking: false
    }, () => {
      this.updateChatHeight(this.data.selectedManual)
      this.scrollToBottom()
    })
  },

  loadConversationList(autoRestore) {
    if (!this.data.appSettings.historyEnabled) {
      this.setData({ conversationList: [], historyLoading: false })
      return
    }
    const userId = this.data.userId || app.globalData.userId || ''
    if (!userId) {
      return
    }

    this.setData({ historyLoading: true })
    const owner = this.captureOwner('list')
    request({ url: `/api/ai/conversations?userId=${encodeURIComponent(userId)}` })
      .then(data => {
        if (!this.owns(owner)) return
        const conversationList = normalizeConversationList(data, this.data.selectedManual)
        this.setData({ conversationList, historyLoading: false })

        if (!autoRestore || this.data.currentConversationId) {
          return
        }

        let target = null
        if (this.data.selectedManual) {
          target = conversationList.find(item => matchConversationToManual(item, this.data.selectedManual)) || null
        } else {
          target = conversationList[0] || null
        }

        if (target) {
          this.loadConversationDetail(target.id, true)
        }
      })
      .catch(err => {
        if (!this.owns(owner)) return
        console.warn('获取会话列表失败', err)
        this.setData({ historyLoading: false })
      })
  },

  refreshConversationList() {
    this.setData({
      conversationList: normalizeConversationList(this.data.conversationList, this.data.selectedManual)
    })
  },

  loadConversationDetail(conversationId, silent) {
    const userId = this.data.userId || app.globalData.userId || ''
    if (!userId || !conversationId) {
      return
    }
    if (!this.data.appSettings.historyEnabled) return
    this.invalidateView()
    this.setData({ currentConversationId: conversationId })
    const owner = this.captureOwner('detail')
    this._detailOwner = owner

    if (!silent) {
      this._loadingOwner = owner
      wx.showLoading({ title: '加载中...', mask: true })
    }

    request({
      url: `/api/ai/conversations/${conversationId}?userId=${encodeURIComponent(userId)}`
    })
      .then(data => {
        if (!this.owns(owner)) return
        const conversation = data && data.conversation ? data.conversation : null
        if (!conversation || conversation.id !== conversationId) throw new Error('conversation_id_mismatch')
        const messages = normalizeConversationMessages(data && data.messages)
        const selectedManual = buildSelectedManualFromConversation(conversation)
        this.setData({
          currentConversationId: conversation && conversation.id ? conversation.id : '',
          selectedManual,
          messages,
          historyVisible: false,
          asking: false,
          pendingImage: ''
        }, () => {
          this.updateChatHeight(selectedManual)
          this.refreshConversationList()
          this.scrollToBottom()
        })
      })
      .catch(err => {
        if (!this.owns(owner)) return
        console.warn('获取会话详情失败', err)
        wx.showToast({ title: '加载会话失败', icon: 'none' })
      })
      .finally(() => {
        if (this._detailOwner === owner) this._detailOwner = null
        if (!silent && this.owns(owner) && this._loadingOwner === owner) {
          this._loadingOwner = null
          wx.hideLoading()
        }
      })
  },

  onConversationTap(e) {
    const conversationId = e.currentTarget.dataset.id
    if (!conversationId) {
      return
    }
    this.loadConversationDetail(conversationId, false)
  },

  onConversationManageTap(e) {
    const dataset = e.currentTarget.dataset || {}
    const conversationId = dataset.id || ''
    const title = dataset.title || '新对话'
    if (!conversationId) {
      return
    }

    wx.showActionSheet({
      itemList: ['重命名会话', '删除会话'],
      success: res => {
        if (res.tapIndex === 0) {
          this.setData({
            renameVisible: true,
            renameSaving: false,
            renameConversationId: conversationId,
            renameValue: title
          })
          return
        }

        if (res.tapIndex === 1) {
          this.confirmDeleteConversation(conversationId)
        }
      }
    })
  },

  onRenameDialogTap() {},

  onRenameInput(e) {
    this.setData({ renameValue: e.detail.value || '' })
  },

  onRenameCancel() {
    if (this.data.renameSaving) {
      return
    }
    this.setData({
      renameVisible: false,
      renameSaving: false,
      renameConversationId: '',
      renameValue: ''
    })
  },

  onRenameConfirm() {
    const conversationId = this.data.renameConversationId
    const userId = this.data.userId || app.globalData.userId || ''
    const title = trimText(this.data.renameValue)

    if (!conversationId || !userId) {
      return
    }
    if (!title) {
      wx.showToast({ title: '请输入会话名称', icon: 'none' })
      return
    }

    this.setData({ renameSaving: true })
    request({
      url: `/api/ai/conversations/${conversationId}/title`,
      method: 'PATCH',
      data: {
        userId,
        title
      }
    })
      .then(data => {
        const nextList = this.data.conversationList.map(item => (
          item.id === conversationId ? { ...item, title: trimText(data && data.title) || title } : item
        ))
        this.setData({
          renameVisible: false,
          renameSaving: false,
          renameConversationId: '',
          renameValue: '',
          conversationList: normalizeConversationList(nextList, this.data.selectedManual)
        })
        wx.showToast({ title: '会话已重命名', icon: 'none' })
      })
      .catch(err => {
        console.warn('重命名会话失败', err)
        this.setData({ renameSaving: false })
        wx.showToast({ title: '重命名失败', icon: 'none' })
      })
  },

  confirmDeleteConversation(conversationId) {
    wx.showModal({
      title: '删除会话',
      content: '删除后无法恢复，是否继续？',
      confirmColor: '#D9485A',
      success: res => {
        if (!res.confirm) {
          return
        }
        this.deleteConversation(conversationId)
      }
    })
  },

  deleteConversation(conversationId) {
    const userId = this.data.userId || app.globalData.userId || ''
    const deletingCurrent = conversationId === this.data.currentConversationId
    if (!conversationId || !userId) {
      return
    }
    this.invalidateView()
    const owner = this.captureOwner('delete')

    request({
      url: `/api/ai/conversations/${conversationId}?userId=${encodeURIComponent(userId)}`,
      method: 'DELETE'
    })
      .then(() => {
        if (!this.owns(owner)) return
        const nextList = this.data.conversationList.filter(item => item.id !== conversationId)
        const nextData = {
          conversationList: normalizeConversationList(nextList, this.data.selectedManual)
        }

        if (deletingCurrent) {
          nextData.currentConversationId = ''
          nextData.messages = [buildDefaultAiMessage()]
          nextData.asking = false
          nextData.inputText = ''
          nextData.pendingImage = ''
        }

        this.setData(nextData, () => {
          if (deletingCurrent) {
            this.updateChatHeight(this.data.selectedManual)
            this.scrollToBottom()
          }
        })
        wx.showToast({ title: '会话已删除', icon: 'none' })
      })
      .catch(err => {
        if (!this.owns(owner)) return
        console.warn('删除会话失败', err)
        wx.showToast({ title: '删除失败', icon: 'none' })
      })
  },

  onInputChange(e) {
    this.setData({ inputText: e.detail.value })
  },

  initRecorder() {
    if (!wx.getRecorderManager) {
      return
    }
    this.recorderManager = wx.getRecorderManager()
    this.recorderManager.onStart(() => {
      this.setData({ recording: true, voiceStatusText: '正在录音，再次点击结束' })
    })
    this.recorderManager.onStop(res => {
      this.setData({ recording: false })
      if (!res || !res.tempFilePath) {
        this.setData({ voiceStatusText: '' })
        wx.showToast({ title: '没有获取到录音文件', icon: 'none' })
        return
      }
      if (Number(res.duration || 0) < 500) {
        this.setData({ voiceStatusText: '' })
        wx.showToast({ title: '录音时间太短', icon: 'none' })
        return
      }
      this.uploadVoiceForTranscription(res.tempFilePath)
    })
    this.recorderManager.onError(error => {
      console.warn('录音失败', error)
      this.setData({ recording: false, transcribing: false, voiceStatusText: '' })
      wx.showToast({ title: '录音失败，请检查麦克风权限', icon: 'none' })
    })
  },

  onVoiceTap() {
    if (this.data.transcribing || !this.recorderManager) {
      if (!this.recorderManager) {
        wx.showToast({ title: '当前微信版本不支持录音', icon: 'none' })
      }
      return
    }
    if (this.data.recording) {
      this.recorderManager.stop()
      return
    }
    wx.authorize({
      scope: 'scope.record',
      success: () => this.startVoiceRecording(),
      fail: () => {
        wx.showModal({
          title: '需要麦克风权限',
          content: '请在设置中允许使用麦克风，才能进行语音提问。',
          confirmText: '去设置',
          success: res => {
            if (res.confirm) wx.openSetting()
          }
        })
      }
    })
  },

  startVoiceRecording() {
    this.recorderManager.start({
      duration: 60000,
      sampleRate: 16000,
      numberOfChannels: 1,
      encodeBitRate: 48000,
      format: 'PCM'
    })
  },

  uploadVoiceForTranscription(filePath) {
    const userId = this.data.userId || app.globalData.userId || ''
    if (!userId) {
      this.setData({ voiceStatusText: '' })
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    this.setData({ transcribing: true, voiceStatusText: '正在识别语音...' })
    wx.uploadFile({
      url: buildApiUrl('/api/ai/transcriptions'),
      filePath,
      name: 'audio',
      header: { 'X-User-Id': userId },
      success: res => {
        if (res.statusCode < 200 || res.statusCode >= 300) {
          console.warn('语音识别接口异常', res)
          wx.showToast({ title: '语音识别服务异常', icon: 'none' })
          return
        }
        let payload = {}
        try {
          payload = typeof res.data === 'string' ? JSON.parse(res.data) : (res.data || {})
        } catch (error) {
          console.warn('语音识别响应解析失败', error)
          wx.showToast({ title: '语音识别响应无效', icon: 'none' })
          return
        }
        const text = trimText(payload.text)
        if (!text) {
          wx.showToast({ title: '没有识别到有效语音', icon: 'none' })
          return
        }
        this.setData({ inputText: text })
        wx.showToast({ title: '语音已转成文字', icon: 'success' })
      },
      fail: error => {
        console.warn('语音识别失败', error)
        wx.showToast({ title: '语音识别失败', icon: 'none' })
      },
      complete: () => {
        this.setData({ transcribing: false, voiceStatusText: '' })
      }
    })
  },

  onUploadTap() {
    wx.chooseImage({
      count: 1,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const path = (res.tempFilePaths && res.tempFilePaths[0]) || ''
        if (path) {
          this.setData({ pendingImage: path })
        }
      }
    })
  },

  onRemovePendingImage() {
    this.setData({ pendingImage: '' })
  },

  onRemoveManualRef() {
    this.setData({ selectedManual: null }, () => {
      this.updateChatHeight(null)
      this.refreshConversationList()
    })
  },

  onPreviewImage(e) {
    const url = e.currentTarget.dataset.url
    if (!url) {
      return
    }
    wx.previewImage({
      current: url,
      urls: [url]
    })
  },

  onSourceTap(e) {
    const dataset = e.currentTarget.dataset || {}
    const kind = dataset.kind || ''
    const url = dataset.url || ''
    if (kind === 'web_result' && url) {
      wx.setClipboardData({
        data: url,
        success: () => {
          wx.showToast({ title: '链接已复制', icon: 'none' })
        }
      })
      return
    }

    const refId = dataset.refId || ''
    const manualId = dataset.manualId || ''
    const manualType = inferManualType(refId, manualId, dataset.manualType || '')
    const targetId = manualType === 'mine' ? (refId || manualId) : (manualId || refId)
    const page = Number(dataset.page || 0)

    if (!targetId) {
      wx.showToast({ title: '当前引用未绑定说明书', icon: 'none' })
      return
    }

    const pageQuery = page > 0 ? `&page=${page}` : ''
    wx.navigateTo({
      url: `/pages/appliance/appliance?id=${targetId}&type=${manualType}${pageQuery}`
    })
  },

  onSendTap() {
    const text = (this.data.inputText || '').trim()
    const image = this.data.pendingImage || ''
    const manualRef = this.data.selectedManual

    if (this.data.asking || this._detailOwner) {
      return
    }

    if (!text) {
      wx.showToast({
        title: image ? '当前版本暂不支持图片识别，请输入文字问题' : '请输入问题',
        icon: 'none'
      })
      return
    }

    if (image) {
      wx.showToast({ title: '当前版本暂不支持图片识别，将仅按文字提问', icon: 'none' })
    }

    const userMsg = {
      id: `m${Date.now()}`,
      role: 'user',
      text,
      image,
      manualRef,
      sources: [],
      structuredAnswer: null,
      insufficientContext: false
    }

    const nextMessages = [...this.data.messages, userMsg]
    this.setData({
      messages: nextMessages,
      inputText: '',
      pendingImage: '',
      asking: true
    }, () => {
      this.scrollToBottom()
      this.requestAiReply(userMsg)
    })
  },

  requestAiReply(userMsg) {
    const historyEnabled = this.data.appSettings.historyEnabled !== false
    const currentUser = this.data.userId || app.globalData.userId || ''
    if (userMsg._requestUser !== undefined && (userMsg._requestUser !== currentUser || userMsg._requestEpoch !== (this._viewEpoch || 0))) {
      wx.showToast({ title: '原请求所属视图已切换，请先查看历史', icon: 'none' })
      return
    }
    userMsg._requestUser = currentUser
    userMsg._requestEpoch = this._viewEpoch || 0
    if (!userMsg.requestId) userMsg.requestId = `qa-${Date.now()}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`
    const owner = this.captureOwner('answer')
    this._loadingOwner = owner
    wx.showLoading({ title: '思考中...', mask: true })
    request({
      url: '/api/qa',
      method: 'POST',
      data: {
        userId: this.data.userId || app.globalData.userId || '',
        requestId: userMsg.requestId,
        persistHistory: historyEnabled,
        userOpenId: app.globalData.openid || '',
        productId: '',
        manualId: userMsg.manualRef ? (userMsg.manualRef.manualId || userMsg.manualRef.id || '') : '',
        manualRefId: userMsg.manualRef ? (userMsg.manualRef.id || userMsg.manualRef.manualId || '') : '',
        manualName: userMsg.manualRef ? (userMsg.manualRef.name || '') : '',
        manualType: userMsg.manualRef ? (userMsg.manualRef.type || '') : '',
        conversationId: historyEnabled ? (this.data.currentConversationId || '') : '',
        query: userMsg.text
      }
    })
      .then(data => {
        if (!this.owns(owner)) return
        if (data && data.requestId && data.requestId !== userMsg.requestId) throw new Error('request_id_mismatch')
        if (owner.conversationId && data && data.conversationId && owner.conversationId !== data.conversationId) throw new Error('conversation_id_mismatch')
        const aiMsg = {
          id: `m${Date.now() + 1}`,
          role: 'ai',
          text: (data && data.answerText) || '抱歉，我暂时没有生成有效回答。',
          image: '',
          manualRef: userMsg.manualRef || null,
          sources: normalizeSources(data && data.sources),
          structuredAnswer: normalizeStructuredAnswer(data && data.structuredAnswer),
          insufficientContext: !!(data && data.insufficientContext)
        }
        const conversationId = (data && data.conversationId) || this.data.currentConversationId || ''
        this.setData({
          messages: [...this.data.messages, aiMsg],
          asking: false,
          currentConversationId: historyEnabled ? conversationId : ''
        }, () => {
          this.scrollToBottom()
        })
        if (historyEnabled) {
          this.loadConversationList(false)
        }
      })
      .catch(err => {
        if (!this.owns(owner)) return
        console.warn('AI 问答请求失败', err)
        const aiMsg = {
          id: `m${Date.now() + 1}`,
          role: 'ai',
          text: '本次请求结果暂无法确认，请先查看历史或业务状态，避免重复提交写操作。',
          image: '',
          manualRef: userMsg.manualRef || null,
          sources: [],
          structuredAnswer: null,
          insufficientContext: false
        }
        wx.showToast({ title: '问答服务异常', icon: 'none' })
        this.setData({
          messages: [...this.data.messages, aiMsg],
          asking: false
        }, () => {
          this.scrollToBottom()
        })
      })
      .finally(() => {
        if (this.owns(owner) && this._loadingOwner === owner) {
          this._loadingOwner = null
          wx.hideLoading()
        }
      })
  },

  invalidateView() {
    this._viewEpoch = (this._viewEpoch || 0) + 1
    this._localConversationKey = `view-${this._viewEpoch}-${Date.now()}`
    this._requestOwners = {}
    this._detailOwner = null
    if (this._loadingOwner) wx.hideLoading()
    this._loadingOwner = null
    this.setData({ asking: false, historyLoading: false })
  },

  captureOwner(kind) {
    this._requestOwners = this._requestOwners || {}
    const owner = {
      kind, epoch: this._viewEpoch || 0, key: this._localConversationKey || '',
      userId: this.data.userId || app.globalData.userId || '',
      historyEnabled: this.data.appSettings.historyEnabled !== false,
      conversationId: this.data.currentConversationId || ''
    }
    this._requestOwners[kind] = owner
    return owner
  },

  owns(owner) {
    return !this._unloaded && this._requestOwners && this._requestOwners[owner.kind] === owner
      && owner.epoch === (this._viewEpoch || 0) && owner.key === (this._localConversationKey || '')
      && owner.userId === (this.data.userId || app.globalData.userId || '')
      && owner.historyEnabled === (this.data.appSettings.historyEnabled !== false)
  },

  onHistoryEmptyAction() {
    this.setData({ historyVisible: false }, () => {
      this.scrollToBottom()
    })
  }
})
