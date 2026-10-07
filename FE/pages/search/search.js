const { request } = require('../../utils/request')

const SEARCH_HISTORY_KEY = 'searchHistory'
let historyCache = []

function normalizeAssetUrl(url, baseUrl) {
  if (!url) return ''
  return url.startsWith('/') ? `${baseUrl}${url}` : url
}

function readHistory() {
  try {
    const raw = wx.getStorageSync(SEARCH_HISTORY_KEY)
    if (Array.isArray(raw)) return raw
    if (typeof raw === 'string' && raw) {
      const parsed = JSON.parse(raw)
      return Array.isArray(parsed) ? parsed : []
    }
    return Array.isArray(historyCache) ? historyCache : []
  } catch (e) {
    return Array.isArray(historyCache) ? historyCache : []
  }
}

function writeHistory(list) {
  historyCache = Array.isArray(list) ? list : []
  try {
    wx.setStorageSync(SEARCH_HISTORY_KEY, historyCache)
  } catch (e) {
    // ignore storage write failure, keep in-memory cache
  }
}

const DEFAULT_MANUALS = [
  { id: 1, name: '美的空调说明书', model: 'KFR-35GW', brandName: '美的', image: '' },
  { id: 2, name: '海尔洗衣机说明书', model: 'XQG100', brandName: '海尔', image: '' },
  { id: 3, name: '格力空调说明书', model: 'KFR-26GW', brandName: '格力', image: '' },
  { id: 4, name: '小米空气净化器说明书', model: 'AC-MD2', brandName: '小米', image: '' },
  { id: 5, name: '苏泊尔电饭煲说明书', model: 'CFXB40', brandName: '苏泊尔', image: '' },
  { id: 6, name: '九阳豆浆机说明书', model: 'DJ13B', brandName: '九阳', image: '' },
  { id: 7, name: '华凌冰箱说明书', model: 'BCD-219', brandName: '华凌', image: '' },
  { id: 8, name: 'TCL电视说明书', model: '55V8G', brandName: 'TCL', image: '' }
]

Page({
  data: {
    statusBarHeight: 44,
    navHeight: 88,
    scrollHeight: 600,

    query: '',
    searched: false,
    searchResults: [],
    allManuals: DEFAULT_MANUALS,

    historyList: [],
    historyDisplayList: [],
    historyExpanded: false,
    selectedImage: ''
  },

  onLoad() {
    const sysInfo = wx.getSystemInfoSync()
    const statusBarHeight = sysInfo.statusBarHeight || 44
    const navHeight = statusBarHeight + 44
    const windowHeight = sysInfo.windowHeight || 600
    const scrollHeight = windowHeight - navHeight

    const initialHistory = readHistory()
    historyCache = initialHistory

    this.setData({
      statusBarHeight,
      navHeight,
      scrollHeight
    })
    this.syncHistoryState(initialHistory, false)

    this.fetchAllManuals()
  },

  onShow() {
    const latestHistory = readHistory()
    historyCache = latestHistory
    this.syncHistoryState(latestHistory, this.data.historyExpanded)
  },

  syncHistoryState(list, expanded) {
    const safeList = Array.isArray(list) ? list : []
    const safeExpanded = !!expanded
    const displayList = safeExpanded ? safeList : safeList.slice(0, 3)
    this.setData({
      historyList: safeList,
      historyDisplayList: displayList,
      historyExpanded: safeExpanded
    })
  },

  fetchAllManuals() {
    request({ url: '/api/manuals' })
      .then(data => {
        if (!Array.isArray(data) || data.length === 0) {
          this.setData({ allManuals: DEFAULT_MANUALS })
          return
        }

        const baseUrl = getApp().globalData.baseUrl
        const manuals = data.map(item => ({
          id: item.id,
          name: item.productName || item.modelName || '未命名',
          model: item.modelName || '',
          brandName: item.brandName || '',
          image: normalizeAssetUrl(item.imageUrl || '', baseUrl),
          intro: item.summary || '',
          manualContent: item.manualContent || '',
          pdfUrl: normalizeAssetUrl(item.pdfUrl || '', baseUrl)
        }))
        this.setData({ allManuals: manuals })
      })
      .catch(() => {
        this.setData({ allManuals: DEFAULT_MANUALS })
      })
  },

  onBackTap() {
    wx.navigateBack({
      fail: () => wx.switchTab({ url: '/pages/index/index' })
    })
  },

  onInputChange(e) {
    this.setData({ query: e.detail.value || '' })
  },

  onInputBlur(e) {
    const value = (e && e.detail && typeof e.detail.value === 'string' ? e.detail.value : this.data.query || '').trim()
    if (!value) return
    this.saveHistory(value)
  },

  onSearchConfirm(e) {
    const eventValue = e && e.detail && typeof e.detail.value === 'string' ? e.detail.value : ''
    const keyword = (eventValue || this.data.query || '').trim()
    this.doSearch(keyword)
  },

  onCameraTap() {
    wx.showActionSheet({
      itemList: ['扫码', '拍照', '上传图片'],
      success: res => {
        if (res.tapIndex === 0) return this.onScanTap()
        if (res.tapIndex === 1) return this.chooseImageFrom(['camera'])
        this.chooseImageFrom(['album'])
      }
    })
  },

  doSearch(rawKeyword) {
    const keyword = (rawKeyword || '').trim()
    this.setData({ query: keyword })

    if (keyword) this.saveHistory(keyword)

    const source = this.data.allManuals || []
    const lower = keyword.toLowerCase()
    const list = !keyword
      ? source
      : source.filter(item => {
          const target = `${item.name || ''} ${item.model || ''} ${item.brandName || ''}`.toLowerCase()
          return target.includes(lower)
        })

    this.setData({
      searched: true,
      searchResults: list
    })
  },

  onClearSearchTap() {
    const currentQuery = (this.data.query || '').trim()
    if (currentQuery) {
      this.saveHistory(currentQuery)
    }
    const localHistory = (this.data.historyList && this.data.historyList.length) ? this.data.historyList : readHistory()
    this.setData({
      query: '',
      searched: false,
      searchResults: [],
      selectedImage: ''
    })
    this.syncHistoryState(localHistory, this.data.historyExpanded)
  },

  saveHistory(keyword) {
    if (!keyword) return
    const old = readHistory()
    const next = [keyword, ...old.filter(item => item !== keyword)].slice(0, 12)
    writeHistory(next)
    this.syncHistoryState(next, this.data.historyExpanded)
  },

  onHistoryTap(e) {
    const word = (e.currentTarget.dataset.word || '').trim()
    if (!word) return
    this.doSearch(word)
  },

  onToggleHistoryTap() {
    this.syncHistoryState(this.data.historyList, !this.data.historyExpanded)
  },

  onClearHistoryTap() {
    historyCache = []
    try {
      wx.removeStorageSync(SEARCH_HISTORY_KEY)
    } catch (e) {
      // ignore
    }
    this.syncHistoryState([], false)
    wx.showToast({ title: '历史已清空', icon: 'none' })
  },

  onScanTap() {
    wx.scanCode({
      success: res => {
        const result = (res.result || '').trim()
        if (!result) return
        this.doSearch(result)
      },
      fail: err => {
        if (err && err.errMsg && err.errMsg.includes('cancel')) return
        wx.showToast({ title: '扫码失败', icon: 'none' })
      }
    })
  },

  chooseImageFrom(sourceType) {
    wx.chooseImage({
      count: 1,
      sizeType: ['compressed'],
      sourceType,
      success: chooseRes => {
        const filePath = chooseRes.tempFilePaths && chooseRes.tempFilePaths[0]
        if (!filePath) return
        this.setData({ selectedImage: filePath })
        this.doSearch('')
        wx.showToast({ title: '图片已上传', icon: 'success' })
      }
    })
  },

  onManualTap(e) {
    const item = e.currentTarget.dataset.item
    if (!item || !item.id) return
    wx.navigateTo({
      url: `/pages/appliance/appliance?id=${item.id}&type=platform`
    })
  }
})
