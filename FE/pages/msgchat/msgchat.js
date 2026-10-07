const app = getApp()
const { request } = require('../../utils/request')

Page({
  data: {
    // 导航栏高度
    statusBarHeight: 0,
    navHeight: 0,
    chatHeight: 0,
    safeAreaBottom: 0,

    // 聊天信息
    chatId: '',
    chatName: '消息',
    chatType: 'stranger',
    preview: '',
    chatIcon: '',

    // 免打扰状态
    isMuted: false,

    // 消息列表
    messages: [],

    // 输入内容
    inputText: '',

    // 我的头像
    myAvatar: '',

    // 滚动锚点
    scrollIntoView: '',
    scrollTop: 0
  },

  onLoad(options) {
    const { chatId = '', chatName = '消息', chatType = 'stranger', preview = '', icon = '' } = options || {}

    // 读取系统信息用于自定义导航
    const systemInfo = wx.getSystemInfoSync()
    const statusBarHeight = systemInfo.statusBarHeight || 0
    const navHeight = statusBarHeight + 44

    // 获取安全区底部
    const safeAreaBottom = systemInfo.safeAreaInsets
      ? systemInfo.safeAreaInsets.bottom || 0
      : 0

    // 计算聊天区高度：屏幕高度 - 导航高度 - 输入区高度（约 120rpx + 安全区）
    const windowHeight = systemInfo.windowHeight
    // 输入区约 100px + safeAreaBottom
    const inputAreaHeight = 100 + safeAreaBottom
    const chatHeight = windowHeight - navHeight - inputAreaHeight

    // 读取免打扰状态
    const muteKey = 'mute_' + chatId
    const isMuted = wx.getStorageSync(muteKey) === true

    const userInfo = wx.getStorageSync('userInfo') || {}
    const myAvatar = userInfo.avatarUrl || ''

    const decodedPreview = preview ? decodeURIComponent(preview) : ''
    const decodedIcon = icon ? this.normalizeAssetUrl(decodeURIComponent(icon)) : ''
    const initialMessages = decodedPreview
      ? [{ id: 'preview', role: 'other', avatar: decodedIcon, text: decodedPreview }]
      : []

    this.setData({
      statusBarHeight,
      navHeight,
      chatHeight,
      safeAreaBottom,
      chatId,
      chatName: decodeURIComponent(chatName),
      chatType,
      preview: decodedPreview,
      chatIcon: decodedIcon,
      isMuted,
      myAvatar,
      messages: initialMessages
    })

    app.onLoginReady(() => {
      this.loadThreadMessages()
    })
    this.loadThreadMessages()
  },

  loadThreadMessages() {
    const userId = app.globalData.loginReady ? app.globalData.userId : ''
    const chatId = this.data.chatId
    if (!userId || !chatId) {
      this._setInitialScrollPosition()
      return
    }

    const url = `/api/messages/thread?userId=${encodeURIComponent(userId)}`
      + `&type=${encodeURIComponent(this.data.chatType)}`
      + `&threadId=${encodeURIComponent(chatId)}`

    request({ url })
      .then(resp => {
        const list = Array.isArray(resp)
          ? resp
          : (resp && Array.isArray(resp.items) ? resp.items : [])
        const messages = list.map(item => ({
          id: item.id || String(Date.now() + Math.random()),
          role: item.role || 'other',
          avatar: this.normalizeAssetUrl(item.avatar || this.data.chatIcon || ''),
          text: item.text || ''
        }))

        if (messages.length) {
          this.setData({ messages }, () => {
            this._scrollToTop()
          })
        } else {
          this._setInitialScrollPosition()
        }
      })
      .catch(() => {
        this._setInitialScrollPosition()
      })
  },

  // 清除聊天记录
  onClearChatTap() {
    wx.showModal({
      title: '清除聊天记录',
      content: '确定清除所有聊天记录吗？',
      success: res => {
        if (res.confirm) {
          this.deleteCurrentThread()
          return
          this.setData({ messages: [] }, () => {
            this._scrollToTop()
          })
          wx.showToast({ title: '已清除', icon: 'success', duration: 1200 })
        }
      }
    })
  },

  // 切换免打扰
  deleteCurrentThread() {
    const userId = app.globalData.loginReady ? app.globalData.userId : ''
    const chatId = this.data.chatId
    const chatType = this.data.chatType || 'system'
    if (!userId || !chatId) {
      this.finishClearAndExit()
      return
    }
    request({
      url: `/api/messages/thread?userId=${encodeURIComponent(userId)}&type=${encodeURIComponent(chatType)}&threadId=${encodeURIComponent(chatId)}`,
      method: 'DELETE'
    })
      .then(() => {
        this.finishClearAndExit()
      })
      .catch(() => {
        wx.showToast({ title: '清除失败', icon: 'none', duration: 1200 })
      })
  },

  finishClearAndExit() {
    this.setData({ messages: [], inputText: '' })
    wx.showToast({ title: '清除成功', icon: 'success', duration: 900 })
    setTimeout(() => {
      wx.navigateBack({
        fail: () => wx.switchTab({ url: '/pages/message/message' })
      })
    }, 650)
  },

  onMuteTap() {
    const newMuted = !this.data.isMuted
    const muteKey = 'mute_' + this.data.chatId
    wx.setStorageSync(muteKey, newMuted)
    this.setData({ isMuted: newMuted })
    wx.showToast({
      title: newMuted ? '已开启免打扰' : '已关闭免打扰',
      icon: 'none',
      duration: 1500
    })
  },

  // 返回
  onBackTap() {
    wx.navigateBack()
  },

  // 输入变化
  onInputChange(e) {
    this.setData({ inputText: e.detail.value })
  },

  // 发送消息
  onSendTap() {
    const text = (this.data.inputText || '').trim()
    if (!text) return

    const newMsg = {
      id: Date.now(),
      role: 'me',
      text
    }

    const messages = [...this.data.messages, newMsg]
    this.setData({ messages, inputText: '' }, () => {
      this._scrollToBottom()
    })
  },

  // 初始进入聊天页时：未占满一屏则置顶，占满一屏则到底部
  _setInitialScrollPosition() {
    this._scrollToTop()
  },

  _scrollToTop() {
    this.setData({
      scrollIntoView: '',
      scrollTop: 0
    })
  },

  // 滚动到底部
  _scrollToBottom() {
    const messages = this.data.messages
    if (!messages || messages.length === 0) {
      this._scrollToTop()
      return
    }
    const lastId = messages[messages.length - 1].id
    this.setData({
      scrollTop: 999999,
      scrollIntoView: 'msg-' + lastId
    })
  },

  normalizeAssetUrl(url) {
    if (!url) return ''
    if (/^https?:\/\//i.test(url) || url.startsWith('wxfile://') || url.startsWith('cloud://')) {
      return url
    }
    if (url.startsWith('/api/')) {
      return (app.globalData.baseUrl || '').replace(/\/$/, '') + url
    }
    return url
  }
})
