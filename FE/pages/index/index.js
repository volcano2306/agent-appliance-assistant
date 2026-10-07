const app = getApp()
const { request } = require('../../utils/request')

const LOCAL_UPLOADED_MANUALS_KEY = 'local_uploaded_manuals'

function normalizeAssetUrl(url, baseUrl) {
  if (!url) return ''
  if (/^https?:\/\//i.test(url) || url.startsWith('wxfile://') || url.startsWith('cloud://')) {
    return url
  }
  return url.startsWith('/api/') ? baseUrl + url : url
}

Page({
  data: {
    statusBarHeight: 44,
    navHeight: 88,
    scrollHeight: 600,

    tabBarHeight: 32,

    navItems: [
      { type: 'tutorial', label: '教程指引', iconImage: '/images/home/MCPNG/black_board_2_line.png' },
      { type: 'category', label: '分类',     iconImage: '/images/home/MCPNG/classify_2_ai_line.png' },
      { type: 'science',  label: '科普',     iconImage: '/images/home/MCPNG/light_line.png' }
    ],

    manuals: [],
    deleteManualId: '',
    deleting: false
  },

  onLoad() {
    const sysInfo = wx.getSystemInfoSync();
    const statusBarHeight = sysInfo.statusBarHeight || 44;
    // 导航栏总高度 = 状态栏 + 44px（自定义导航栏内容高度）
    const navHeight = statusBarHeight + 44;
    const windowHeight = sysInfo.windowHeight || 600;
    // 滚动区域 = 窗口高度 - 导航栏高度 - 底部TabBar预留(约55px)
    // 滚动区域 = 整个窗口高度 - 导航栏高度（内容可滚动到TabBar顶部）
    const scrollHeight = windowHeight - navHeight;
    // TabBar横条高度（rpx转px: 64rpx）
    const tabBarBarPx = Math.round(64 / 750 * sysInfo.windowWidth);
    // 安全区域底部间距
    const safeAreaBottom = sysInfo.screenHeight - (sysInfo.safeArea ? sysInfo.safeArea.bottom : sysInfo.screenHeight);
    // TabBar总占位 = 横条高度 + 安全区域
    const tabBarHeight = tabBarBarPx + safeAreaBottom;

    this.setData({
      statusBarHeight,
      navHeight,
      scrollHeight,
      tabBarHeight
    })

    app.onLoginReady(userId => this.fetchManuals(userId))
  },

  fetchManuals(userId) {
    if (!userId) {
      this.setData({ manuals: [], deleteManualId: '' })
      return
    }
    const baseUrl = getApp().globalData.baseUrl
    const localManuals = this.getLocalManuals(userId)
    const uploadFallbacks = this.getUploadFallbacks(userId)
    request({ url: '/api/my-manuals?userId=' + userId })
      .then(data => {
        const remoteList = Array.isArray(data)
          ? data
          : (Array.isArray(data && data.items) ? data.items : [])

        const remoteManuals = remoteList.map(item => {
          const fallback = this.findUploadFallback(item, uploadFallbacks)
          const imageUrl = item.imageUrl || item.coverImageUrl || item.thumbnail || item.previewImageUrl || (fallback && fallback.image) || ''
          return {
            id: item.id,
            name: item.rename || item.oname || item.productName || item.model || item.modelName || '未命名',
            image: normalizeAssetUrl(imageUrl, baseUrl),
            pdfUrl: normalizeAssetUrl(item.pdfUrl || item.uploadedFileUrl || item.fileUrl || (fallback && fallback.filePath) || '', baseUrl),
            brandName: item.brandName || '',
            model: item.model || item.modelName || '',
            isLocal: false
          }
        })
        const dedupedLocalManuals = localManuals.filter(local => !remoteManuals.some(remote => this.isSameManual(local, remote)))
        const nextManuals = [...dedupedLocalManuals, ...remoteManuals]
        this.setData({ manuals: nextManuals, deleteManualId: '' })
      })
      .catch(err => {
        console.warn('获取说明书列表失败', err)
        this.setData({ manuals: localManuals, deleteManualId: '' })
      })
  },

  getLocalManuals(userId) {
    const baseUrl = getApp().globalData.baseUrl
    const list = wx.getStorageSync(LOCAL_UPLOADED_MANUALS_KEY) || []
    return list
      .filter(item => !userId || item.ownerUserId === userId)
      .filter(item => !item.syncedRemoteId)
      .map(item => ({
        id: item.id,
        name: item.productName || item.name || '未命名',
        image: normalizeAssetUrl(item.image || item.imageUrl || '', baseUrl),
        pdfUrl: normalizeAssetUrl(item.filePath || item.pdfUrl || '', baseUrl),
        brandName: item.brandName || '',
        model: item.modelName || '',
        intro: '本地上传说明书，后端同步后可在全平台查看。',
        manualContent: '本地上传文件暂不支持在线预览，请点击下载/打开文档查看。',
        isLocal: true
      }))
  },

  getUploadFallbacks(userId) {
    const list = wx.getStorageSync(LOCAL_UPLOADED_MANUALS_KEY) || []
    return list.filter(item => !userId || item.ownerUserId === userId)
  },

  findUploadFallback(item, list) {
    if (!item || !Array.isArray(list) || list.length === 0) return null
    const remoteId = item.id || ''
    const manualId = item.manualId || ''
    const name = item.rename || item.oname || item.productName || ''
    const brand = item.brandName || ''
    const model = item.model || item.modelName || ''
    return list.find(local => {
      if (local.syncedRemoteId && (local.syncedRemoteId === remoteId || local.syncedRemoteId === manualId)) return true
      if (name && (local.productName === name || local.name === name)) return true
      return !!(brand && model && local.brandName === brand && local.modelName === model)
    }) || null
  },

  isSameManual(a, b) {
    if (!a || !b) return false
    const nameA = (a.name || a.productName || '').trim()
    const nameB = (b.name || b.productName || '').trim()
    const brandA = (a.brandName || '').trim()
    const brandB = (b.brandName || '').trim()
    const modelA = (a.model || a.modelName || '').trim()
    const modelB = (b.model || b.modelName || '').trim()
    if (nameA && nameB && nameA === nameB) return true
    return !!(brandA && brandB && modelA && modelB && brandA === brandB && modelA === modelB)
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 0 })
    }
    const userId = getApp().globalData.loginReady ? getApp().globalData.userId : ''
    this.fetchManuals(userId)
  },

  onSearchTap() {
    wx.navigateTo({ url: '/pages/search/search' })
  },

  onQuarterTap() {
    wx.navigateTo({ url: '/pages/ai/ai' })
  },

  onNavTap(e) {
    const type = e.currentTarget.dataset.type
    if (type === 'category') {
      wx.navigateTo({ url: '/pages/category/category' })
      return
    }
    if (type === 'tutorial' || type === 'science') {
      wx.showToast({ title: '该功能暂未开发', icon: 'none' })
    }
  },

  onManualTap(e) {
    const item = e.currentTarget.dataset.item
    if (this.data.deleteManualId) {
      this.setData({ deleteManualId: '' })
      return
    }
    if (item.isLocal) {
      wx.navigateTo({
        url: `/pages/appliance/appliance?data=${encodeURIComponent(JSON.stringify({
          id: item.id,
          name: item.name,
          image: item.image,
          intro: item.intro,
          manualContent: item.manualContent,
          pdfUrl: item.pdfUrl,
          brandName: item.brandName,
          modelName: item.model,
          category: '家电'
        }))}`
      })
      return
    }
    wx.navigateTo({
      url: `/pages/appliance/appliance?id=${item.id}&type=mine`
    })
  },

  onManualLongPress(e) {
    const id = e.currentTarget.dataset.id || ''
    if (!id) return
    this.setData({ deleteManualId: id })
  },

  onDeleteTap(e) {
    const id = e.currentTarget.dataset.id || ''
    if (!id || this.data.deleting) return

    const manual = (this.data.manuals || []).find(item => item.id === id)
    if (manual && manual.isLocal) {
      wx.showModal({
        title: '删除说明书',
        content: '确认删除该本地说明书吗？',
        success: ({ confirm }) => {
          if (!confirm) return
          const list = wx.getStorageSync(LOCAL_UPLOADED_MANUALS_KEY) || []
          wx.setStorageSync(LOCAL_UPLOADED_MANUALS_KEY, list.filter(item => item.id !== id))
          const nextManuals = (this.data.manuals || []).filter(item => item.id !== id)
          this.setData({
            manuals: nextManuals,
            deleteManualId: ''
          })
          wx.showToast({ title: '已删除', icon: 'success' })
        }
      })
      return
    }

    const userId = getApp().globalData.userId
    if (!userId) {
      wx.showToast({ title: '登录状态失效', icon: 'none' })
      return
    }

    wx.showModal({
      title: '删除说明书',
      content: '确认从我的说明书中删除该项吗？',
      success: ({ confirm }) => {
        if (!confirm) return

        this.setData({ deleting: true })
        request({
          url: '/api/my-manuals/' + encodeURIComponent(id) + '?userId=' + encodeURIComponent(userId),
          method: 'DELETE'
        })
          .then(() => {
            wx.showToast({ title: '已删除', icon: 'success' })
            this.fetchManuals(userId)
          })
          .catch(() => {
            wx.showToast({ title: '删除失败', icon: 'none' })
          })
          .finally(() => {
            this.setData({ deleting: false, deleteManualId: '' })
          })
      }
    })
  },

  onManualSectionTap() {
    if (this.data.deleteManualId) {
      this.setData({ deleteManualId: '' })
    }
  }
})
