const app = getApp()
const { request } = require('../../utils/request')
const { loadSettings, updateSettings, SETTINGS_STORAGE_KEY } = require('../../utils/settings')

function formatCacheSize(sizeKb) {
  const value = Number(sizeKb || 0)
  if (value <= 0) return '0 KB'
  if (value >= 1024) return `${(value / 1024).toFixed(2)} MB`
  return `${value.toFixed(0)} KB`
}

Page({
  data: {
    statusBarHeight: 44,
    navHeight: 88,
    safeAreaBottom: 0,
    appVersion: '1.0.0',
    settings: loadSettings(),
    cacheSizeText: '0 KB'
  },

  onLoad() {
    const sysInfo = wx.getSystemInfoSync()
    const statusBarHeight = sysInfo.statusBarHeight || 44
    const navHeight = statusBarHeight + 44
    const safeAreaBottom = sysInfo.screenHeight - (sysInfo.safeArea ? sysInfo.safeArea.bottom : sysInfo.screenHeight)
    this.setData({ statusBarHeight, navHeight, safeAreaBottom })
    this.refreshPageData()
  },

  onShow() {
    this.refreshPageData()
  },

  refreshPageData() {
    const settings = loadSettings()
    const storageInfo = wx.getStorageInfoSync()
    this.setData({
      settings,
      cacheSizeText: formatCacheSize(storageInfo.currentSize || 0)
    })
  },

  onBackTap() {
    wx.navigateBack({
      fail: () => wx.switchTab({ url: '/pages/profile/profile' })
    })
  },

  updateSettingAndState(patch) {
    const settings = updateSettings(patch)
    app.globalData.settings = settings
    this.setData({ settings })
  },

  onHistorySwitch(e) {
    const value = !!e.detail.value
    this.updateSettingAndState({ historyEnabled: value })
    wx.showToast({ title: value ? '已开启聊天历史保存' : '已关闭聊天历史保存', icon: 'none' })
  },

  onSourceSwitch(e) {
    const value = !!e.detail.value
    this.updateSettingAndState({ showSources: value })
    wx.showToast({ title: value ? '将显示说明书引用' : '已隐藏说明书引用', icon: 'none' })
  },

  onClearHistoryTap() {
    const userId = app.globalData.userId || wx.getStorageSync('sms_user_id') || ''
    if (!userId) {
      wx.showToast({ title: '当前未登录', icon: 'none' })
      return
    }

    wx.showModal({
      title: '清空 AI 历史',
      content: '会删除当前账号下所有 AI 会话记录，且无法恢复。是否继续？',
      confirmText: '清空',
      confirmColor: '#D9485A',
      success: ({ confirm }) => {
        if (!confirm) return
        request({
          url: `/api/ai/conversations?userId=${encodeURIComponent(userId)}`,
          method: 'DELETE'
        })
          .then(() => {
            wx.showToast({ title: 'AI 历史已清空', icon: 'success' })
          })
          .catch((err) => {
            console.warn('清空 AI 历史失败', err)
            wx.showToast({ title: '清空失败', icon: 'none' })
          })
      }
    })
  },

  onClearCacheTap() {
    wx.showModal({
      title: '清理本地缓存',
      content: '会清理搜索历史和页面缓存等本地数据，但保留账号信息和当前设置。是否继续？',
      confirmText: '清理',
      confirmColor: '#2B5A4A',
      success: ({ confirm }) => {
        if (!confirm) return
        const keepUserId = wx.getStorageSync('sms_user_id')
        const keepJoinAt = wx.getStorageSync('sms_join_at')
        const keepUserInfo = wx.getStorageSync('userInfo')
        const keepSettings = wx.getStorageSync(SETTINGS_STORAGE_KEY)

        wx.clearStorageSync()
        if (keepUserId) wx.setStorageSync('sms_user_id', keepUserId)
        if (keepJoinAt) wx.setStorageSync('sms_join_at', keepJoinAt)
        if (keepUserInfo) wx.setStorageSync('userInfo', keepUserInfo)
        if (keepSettings) wx.setStorageSync(SETTINGS_STORAGE_KEY, keepSettings)

        app.globalData.settings = loadSettings()
        this.refreshPageData()
        wx.showToast({ title: '缓存已清理', icon: 'success' })
      }
    })
  },

  onAboutTap() {
    wx.showModal({
      title: '关于家电轻手册',
      content: `版本 ${this.data.appVersion}\n\n家电轻手册围绕说明书检索、AI 问答和家电使用辅助提供服务。`,
      confirmText: '关闭',
      showCancel: false
    })
  },

  onPolicyTap() {
    wx.showModal({
      title: '协议与隐私',
      content: '当前版本先提供基础能力，协议与隐私内容将随正式版本完善。',
      confirmText: '知道了',
      showCancel: false
    })
  }
})
