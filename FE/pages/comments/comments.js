const app = getApp()
const { request } = require('../../utils/request')
const LOCAL_UPLOADED_MANUALS_KEY = 'local_uploaded_manuals'

function interactionReadKey(userId, type) {
  return `interaction_read_${type}_${userId}`
}

function normalizeMediaUrl(url) {
  const value = (url || '').trim()
  if (!value) return ''
  if (/^(https?:)?\/\//i.test(value) || value.startsWith('wxfile://') || value.startsWith('cloud://') || value.startsWith('data:')) {
    return value
  }
  const base = ((app.globalData && app.globalData.baseUrl) || '').replace(/\/$/, '')
  const path = value.startsWith('/') ? value : `/${value}`
  return base + path
}

function formatFriendlyTime(timestamp) {
  const time = Number(timestamp || 0)
  if (!time) return ''
  const date = new Date(time)
  if (Number.isNaN(date.getTime())) return ''

  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const startOfTargetDay = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
  const dayDiff = Math.floor((startOfToday - startOfTargetDay) / 86400000)
  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')

  if (dayDiff === 0) return `${hh}:${mm}`
  if (dayDiff === 1) return `昨天 ${hh}:${mm}`
  if (dayDiff > 1 && dayDiff <= 7) return `${dayDiff}天前`

  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  if (date.getFullYear() === now.getFullYear()) {
    return `${month}-${day}`
  }
  return `${date.getFullYear()}-${month}-${day}`
}

function getLocalUserProfile() {
  const userInfo = wx.getStorageSync('userInfo') || {}
  return {
    name: (userInfo.name || userInfo.nickName || '').trim(),
    avatarUrl: (userInfo.avatarUrl || '').trim()
  }
}

function getUploadFallbacks(userId) {
  const list = wx.getStorageSync(LOCAL_UPLOADED_MANUALS_KEY) || []
  return list.filter(item => item && (!userId || item.ownerUserId === userId))
}

function resolveThumbFallback(item, uploadFallbacks) {
  if (!item || !Array.isArray(uploadFallbacks) || uploadFallbacks.length === 0) {
    return ''
  }
  const targetId = (item.targetId || '').trim()
  const quoted = (item.quoted || '').trim()
  const content = (item.content || '').trim()

  const matched = uploadFallbacks.find(local => {
    if (!local) return false
    if (targetId && (local.id === targetId || local.syncedRemoteId === targetId)) return true
    if (quoted && (local.productName === quoted || local.name === quoted)) return true
    if (content && (local.productName === content || local.name === content)) return true
    return false
  })
  if (!matched) return ''
  return normalizeMediaUrl(matched.image || matched.imageUrl || '')
}

function normalizeList(data) {
  if (Array.isArray(data)) return data
  if (data && Array.isArray(data.items)) return data.items
  return []
}

function buildMyManualImageMaps(list, uploadFallbacks) {
  const byId = {}
  const byManualId = {}
  const byName = {}
  const locals = Array.isArray(uploadFallbacks) ? uploadFallbacks : []
  normalizeList(list).forEach((item) => {
    if (!item) return
    const matchedLocal = locals.find(local =>
      local && (
        (local.syncedRemoteId && item.id && local.syncedRemoteId === item.id)
        || (local.syncedRemoteId && item.manualId && local.syncedRemoteId === item.manualId)
      )
    )
    const image = normalizeMediaUrl(
      item.imageUrl
      || item.coverImageUrl
      || (matchedLocal && (matchedLocal.image || matchedLocal.imageUrl))
      || ''
    )
    if (!image) return
    if (item.id) byId[String(item.id)] = image
    if (item.manualId) byManualId[String(item.manualId)] = image
    const name = (item.rename || item.oname || item.productName || '').trim()
    if (name) byName[name] = image
  })
  return { byId, byManualId, byName }
}

Page({
  data: {
    statusBarHeight: 0,
    navHeight: 0,
    scrollHeight: 0,
    comments: []
  },

  onLoad() {
    const systemInfo = wx.getSystemInfoSync()
    const statusBarHeight = systemInfo.statusBarHeight || 0
    const navHeight = statusBarHeight + 44
    const scrollHeight = systemInfo.windowHeight - navHeight

    this.setData({ statusBarHeight, navHeight, scrollHeight })
    this.fetchComments()
    app.onLoginReady(() => this.fetchComments())
  },

  onShow() {
    this.markReadNow()
    this.fetchComments()
  },

  markReadNow() {
    const userId = this.resolveUserId()
    if (!userId) return
    wx.setStorageSync(interactionReadKey(userId, 'comments'), Date.now())
    this.refreshTabBadge()
  },

  fetchComments() {
    const userId = this.resolveUserId()
    if (!userId) {
      this.setData({ comments: [] })
      return
    }

    wx.setStorageSync(interactionReadKey(userId, 'comments'), Date.now())
    this.refreshTabBadge()

    Promise.all([
      request({ url: `/api/interactions/received/comments?userId=${encodeURIComponent(userId)}` }),
      request({ url: `/api/my-manuals?userId=${encodeURIComponent(userId)}` }).catch(() => [])
    ])
      .then(([list, myManuals]) => {
        const uploadFallbacks = getUploadFallbacks(userId)
        const imageMaps = buildMyManualImageMaps(myManuals, uploadFallbacks)
        const comments = (Array.isArray(list) ? list : []).map((item) => ({
          ...item,
          fromName: (item.fromName || '').trim() || '用户',
          fromAvatar: normalizeMediaUrl(item.fromAvatar || ''),
          thumbnail:
            normalizeMediaUrl(item.thumbnail || '')
            || imageMaps.byId[String(item.targetId || '')]
            || imageMaps.byManualId[String(item.targetId || '')]
            || imageMaps.byName[(item.quoted || '').trim()]
            || imageMaps.byName[(item.content || '').trim()]
            || resolveThumbFallback(item, uploadFallbacks),
          timeDisplay: formatFriendlyTime(item.timestamp),
          hasActions: true
        }))
        this.setData({ comments })
        wx.setStorageSync(interactionReadKey(userId, 'comments'), Date.now())
        this.refreshTabBadge()
      })
      .catch(() => {
        console.warn('加载评论通知失败')
        this.setData({ comments: [] })
      })
  },

  resolveUserId() {
    if (typeof app.resolveUserId === 'function') {
      return app.resolveUserId() || ''
    }
    return ''
  },

  onBackTap() {
    wx.navigateBack()
  },

  refreshTabBadge() {
    if (typeof this.getTabBar === 'function' && this.getTabBar() && this.getTabBar().refreshBadge) {
      this.getTabBar().refreshBadge()
    }
  },

  onLikeTap(e) {
    const { id } = e.currentTarget.dataset
    const userId = this.resolveUserId()
    if (!userId) {
      wx.showToast({ title: '请先登录', icon: 'none', duration: 1000 })
      return
    }
    const profile = getLocalUserProfile()
    request({
      url: `/api/interactions/comments/${id}/likes/toggle`,
      method: 'POST',
      data: {
        userId,
        fromUserName: profile.name,
        fromAvatar: profile.avatarUrl
      }
    })
      .then(() => {
        wx.showToast({ title: '已完成', icon: 'success', duration: 1000 })
        this.fetchComments()
      })
      .catch(() => {
        wx.showToast({ title: '操作失败', icon: 'none', duration: 1000 })
      })
  },

  onReplyTap(e) {
    const { id } = e.currentTarget.dataset
    const userId = this.resolveUserId()
    if (!userId) {
      wx.showToast({ title: '请先登录', icon: 'none', duration: 1000 })
      return
    }
    wx.showModal({
      title: '回复评论',
      editable: true,
      placeholderText: '输入回复内容',
      success: (res) => {
        const content = (res.content || '').trim()
        if (!res.confirm || !content) return
        const profile = getLocalUserProfile()

        request({
          url: `/api/interactions/comments/${id}/replies`,
          method: 'POST',
          data: {
            userId,
            content,
            fromUserName: profile.name,
            fromAvatar: profile.avatarUrl
          }
        })
          .then(() => {
            wx.showToast({ title: '回复成功', icon: 'success', duration: 1000 })
            this.fetchComments()
          })
          .catch(() => {
            wx.showToast({ title: '回复失败', icon: 'none', duration: 1000 })
          })
      }
    })
  }
})
