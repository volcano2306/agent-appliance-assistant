const app = getApp()
const { request } = require('../../utils/request')
const LOCAL_UPLOADED_MANUALS_KEY = 'local_uploaded_manuals'

function normalizeAssetUrl(url) {
  if (!url) return ''
  if (/^https?:\/\//i.test(url) || url.startsWith('wxfile://') || url.startsWith('cloud://')) {
    return url
  }
  if (url.startsWith('/api/')) {
    const base = (app.globalData.baseUrl || '').replace(/\/$/, '')
    return base + url
  }
  return url
}

Page({
  data: {
    statusBarHeight: 44,
    navHeight: 88,
    scrollHeight: 600,
    safeAreaBottom: 0,
    previewHeight: 420,
    actionBarHeight: 64,
    type: 'platform', // platform | mine

    addingMyManual: false,
    pageImages: [],
    commentsLoading: false,
    comments: [],

    manualLiked: false,
    manualLikeCount: 0,
    manualCollected: false,
    collectedMyManualId: '',

    manual: {
      id: '',
      manualId: '',
      name: '产品名称',
      navTitle: '分类/品牌',
      category: '',
      image: '',
      intro: '暂无产品介绍',
      manualContent: '暂无说明书内容',
      pdfUrl: ''
    }
  },

  onLoad(options) {
    const sysInfo = wx.getSystemInfoSync()
    const statusBarHeight = sysInfo.statusBarHeight || 44
    const navHeight = statusBarHeight + 44
    const windowHeight = sysInfo.windowHeight || 600
    const safeAreaBottom = sysInfo.screenHeight - (sysInfo.safeArea ? sysInfo.safeArea.bottom : sysInfo.screenHeight)
    const actionBarPx = Math.round((112 / 750) * sysInfo.windowWidth)
    const scrollHeight = windowHeight - navHeight - actionBarPx - safeAreaBottom
    const previewHeight = Math.floor(windowHeight * 0.62)
    const type = options.type || 'platform'

    this.setData({
      statusBarHeight,
      navHeight,
      scrollHeight,
      safeAreaBottom,
      previewHeight,
      actionBarHeight: actionBarPx,
      type
    })

    if (options.id) {
      this.fetchManual(options.id, type)
    } else if (options.data) {
      try {
        const parsed = JSON.parse(decodeURIComponent(options.data))
        const image = normalizeAssetUrl(parsed.image || parsed.imageUrl || '')
        const pdfUrl = normalizeAssetUrl(parsed.pdfUrl || parsed.uploadedFileUrl || '')
        const pageImages = (parsed.pageImages || []).map((url) => normalizeAssetUrl(url))
        const category = parsed.categoryCode || parsed.categoryName || parsed.category || ''
        const brandName = parsed.brandName || ''
        const navTitle = this.buildNavTitle(category, brandName, parsed.name || '产品名称')

        this.setData({
          manual: { ...this.data.manual, ...parsed, image, pdfUrl, category, brandName, navTitle },
          pageImages,
          manualCollected: true
        })
        this.loadInteractionData()
      } catch (e) {}
    }

    app.onLoginReady(() => {
      this.loadInteractionData()
    })
  },

  fetchManual(id, type) {
    const url = type === 'mine' ? `/api/my-manuals/${id}` : `/api/manuals/${id}`
    request({ url })
      .then((data) => {
        const fallback = this.findLocalManualFallback(data, id)
        const image = normalizeAssetUrl(
          data.imageUrl || data.coverImageUrl || data.thumbnail || (fallback && (fallback.image || fallback.imageUrl)) || ''
        )
        const pdfUrl = normalizeAssetUrl(data.pdfUrl || data.uploadedFileUrl || (fallback && fallback.filePath) || '')
        const pageImages = (data.pageImages || []).map((u) => normalizeAssetUrl(u))
        const manualId = data.manualId || data.id
        const manual = {
          id: data.id,
          manualId,
          name: data.productName || data.oname || data.name || '未命名',
          navTitle: this.buildNavTitle(
            data.categoryCode || data.categoryName || '',
            data.brandName || '',
            data.productName || data.oname || data.name || '未命名'
          ),
          category: data.categoryCode || data.categoryName || '',
          image,
          intro: data.summary || '',
          manualContent: data.manualContent || '',
          pdfUrl,
          pages: data.pages || pageImages.length,
          brandName: data.brandName || '',
          modelName: data.modelName || data.model || ''
        }
        this.setData({
          manual,
          pageImages,
          manualCollected: type === 'mine',
          collectedMyManualId: type === 'mine' ? (data.id || '') : ''
        })
        this.loadInteractionData()
      })
      .catch((err) => {
        console.warn('获取说明书详情失败', err)
        wx.showToast({ title: '加载失败', icon: 'none' })
      })
  },

  loadInteractionData() {
    this.loadComments()
    this.loadManualLikeState()
    this.loadCollectState()
  },

  getTargetInfo() {
    const manual = this.data.manual || {}
    if (manual.manualId) {
      return { targetType: 'manual', targetId: manual.manualId }
    }
    if (this.data.type === 'mine') {
      return { targetType: 'myManual', targetId: manual.id || '' }
    }
    return { targetType: 'manual', targetId: manual.id || '' }
  },

  findLocalManualFallback(remote, currentId) {
    const list = wx.getStorageSync(LOCAL_UPLOADED_MANUALS_KEY) || []
    const remoteId = (remote && remote.id) || currentId || ''
    const manualId = (remote && remote.manualId) || ''
    const name = (remote && (remote.oname || remote.productName || remote.name || remote.rename)) || ''
    const brand = (remote && remote.brandName) || ''
    const model = (remote && (remote.model || remote.modelName)) || ''
    return (
      list.find((item) => {
        if (!item) return false
        if (item.syncedRemoteId && (item.syncedRemoteId === remoteId || item.syncedRemoteId === manualId)) return true
        if (name && (item.productName === name || item.name === name)) return true
        return !!(brand && model && item.brandName === brand && item.modelName === model)
      }) || null
    )
  },

  loadComments() {
    const target = this.getTargetInfo()
    if (!target.targetId) return

    const userId = this.getLoginUserId()
    const localUser = this.getLocalUserProfile()
    const localAvatar = localUser.avatarUrl || ''
    const localName = localUser.name || '我'
    this.setData({ commentsLoading: true })

    request({
      url: `/api/interactions/comments?targetType=${encodeURIComponent(target.targetType)}&targetId=${encodeURIComponent(target.targetId)}&userId=${encodeURIComponent(userId)}`
    })
      .then((resp) => {
        const list = Array.isArray(resp && resp.items) ? resp.items : []
        const nameByUserId = {}
        const rememberName = (item) => {
          if (!item || !item.fromUserId) return
          const isMine = !!userId && String(item.fromUserId || '') === String(userId)
          const name = isMine ? localName : (item.fromUserName || '')
          if (name) nameByUserId[String(item.fromUserId)] = name
        }
        list.forEach((item) => {
          rememberName(item)
          ;(Array.isArray(item.replies) ? item.replies : []).forEach(rememberName)
        })

        const comments = list.map((item) => {
          const isMine = !!userId && String(item.fromUserId || '') === String(userId)
          const fromUserName = isMine ? localName : (item.fromUserName || '用户')
          const replies = Array.isArray(item.replies) ? item.replies : []
          return {
            ...item,
            fromUserName,
            avatarUrl: (isMine && localAvatar) ? localAvatar : (item.fromAvatar || ''),
            initials: this.getInitial(fromUserName),
            timeText: this.formatTime(item.createdAt),
            replies: replies.map((reply) => {
              const replyIsMine = !!userId && String(reply.fromUserId || '') === String(userId)
              const replyName = replyIsMine ? localName : (reply.fromUserName || '用户')
              const toUserId = reply.toUserId ? String(reply.toUserId) : ''
              const toUserName = toUserId
                ? (nameByUserId[toUserId] || reply.toUserName || '用户')
                : (reply.toUserName || '')
              return {
                ...reply,
                fromUserName: replyName,
                toUserName,
                avatarUrl: (replyIsMine && localAvatar) ? localAvatar : (reply.fromAvatar || ''),
                timeText: this.formatTime(reply.createdAt)
              }
            })
          }
        })
        this.setData({ comments })
      })
      .catch((err) => {
        console.warn('加载评论失败', err)
      })
      .finally(() => {
        this.setData({ commentsLoading: false })
      })
  },

  loadManualLikeState() {
    const target = this.getTargetInfo()
    if (!target.targetId) return

    const userId = this.getLoginUserId()
    request({
      url: `/api/interactions/targets/likes/status?targetType=${encodeURIComponent(target.targetType)}&targetId=${encodeURIComponent(target.targetId)}&userId=${encodeURIComponent(userId)}`
    })
      .then((resp) => {
        this.setData({
          manualLiked: !!(resp && resp.liked),
          manualLikeCount: Number((resp && resp.likeCount) || 0)
        })
      })
      .catch(() => {
        this.setData({ manualLiked: false, manualLikeCount: 0 })
      })
  },

  loadCollectState() {
    const userId = this.getLoginUserId()
    const manual = this.data.manual || {}
    const targetManualId = manual.manualId || manual.id || ''

    if (!targetManualId) {
      this.setData({ manualCollected: false, collectedMyManualId: '' })
      return
    }
    if (!userId) {
      if (this.data.type === 'mine') {
        this.setData({ manualCollected: true, collectedMyManualId: manual.id || '' })
      } else {
        this.setData({ manualCollected: false, collectedMyManualId: '' })
      }
      return
    }

    request({ url: `/api/my-manuals?userId=${encodeURIComponent(userId)}` })
      .then((list) => {
        const rows = Array.isArray(list) ? list : (Array.isArray(list && list.items) ? list.items : [])
        const hit = rows.find((item) => {
          if (!item) return false
          if (this.data.type === 'mine' && item.id === manual.id) return true
          if (item.manualId && item.manualId === targetManualId) return true
          return false
        }) || null
        this.setData({
          manualCollected: !!hit || this.data.type === 'mine',
          collectedMyManualId: hit ? (hit.id || '') : (this.data.type === 'mine' ? (manual.id || '') : '')
        })
      })
      .catch(() => {
        if (this.data.type === 'mine') {
          this.setData({ manualCollected: true, collectedMyManualId: manual.id || '' })
        }
      })
  },

  formatTime(value) {
    if (!value) return ''
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return ''
    const pad = (n) => (n < 10 ? `0${n}` : `${n}`)
    return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
  },

  getInitial(name) {
    const text = (name || '').trim()
    return text ? text[0] : '用'
  },

  getLoginUserId() {
    if (typeof app.resolveUserId === 'function') {
      return app.resolveUserId() || ''
    }
    return ''
  },

  getLocalUserProfile() {
    const userInfo = wx.getStorageSync('userInfo') || {}
    const name = (userInfo.name || userInfo.nickName || '').trim()
    return {
      name: name || '我',
      avatarUrl: userInfo.avatarUrl || ''
    }
  },

  buildNavTitle(category, brand, fallback) {
    const c = this.toCnCategory(category)
    const b = (brand || '').trim()
    if (c && b) return `${c}/${b}`
    if (c) return `${c}/未知品牌`
    if (b) return `分类/${b}`
    return fallback || '分类/品牌'
  },

  toCnCategory(category) {
    const raw = (category || '').toLowerCase()
    if (!raw) return ''
    if (raw.includes('camera') || raw.includes('相机')) return '相机'
    if (raw.includes('jiadian') || raw.includes('appliance') || raw.includes('家电')) return '家电'
    return category
  },

  onDownloadTap() {
    const pdfUrl = this.data.manual.pdfUrl
    if (!pdfUrl) {
      wx.showToast({ title: '暂无 PDF 文件', icon: 'none' })
      return
    }

    wx.showLoading({ title: '下载中...' })
    wx.downloadFile({
      url: pdfUrl,
      success: (res) => {
        wx.hideLoading()
        if (res.statusCode === 200) {
          wx.openDocument({ filePath: res.tempFilePath, showMenu: true })
        } else {
          wx.showToast({ title: '下载失败', icon: 'none' })
        }
      },
      fail: () => {
        wx.hideLoading()
        wx.showToast({ title: '下载失败', icon: 'none' })
      }
    })
  },

  onQuarterTap() {
    const manual = this.data.manual || {}
    const manualRef = {
      id: manual.id || '',
      manualId: manual.manualId || manual.id || '',
      name: manual.name || manual.productName || '未命名说明书',
      type: this.data.type || 'platform'
    }
    wx.navigateTo({
      url: `/pages/ai/ai?manual=${encodeURIComponent(JSON.stringify(manualRef))}`
    })
  },

  onSearchTap() {
    wx.navigateTo({ url: '/pages/search/search' })
  },

  onBackTap() {
    wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/index/index' }) })
  },

  onThumbTap() {
    const userId = this.getLoginUserId()
    if (!userId) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    const target = this.getTargetInfo()
    if (!target.targetId) return
    const profile = this.getLocalUserProfile()
    request({
      url: '/api/interactions/targets/likes/toggle',
      method: 'POST',
      data: {
        targetType: target.targetType,
        targetId: target.targetId,
        userId,
        fromUserName: profile.name,
        fromAvatar: profile.avatarUrl
      }
    })
      .then((resp) => {
        this.setData({
          manualLiked: !!resp.liked,
          manualLikeCount: Number(resp.likeCount || 0)
        })
      })
      .catch(() => {
        wx.showToast({ title: '点赞失败', icon: 'none' })
      })
  },

  onPlusTap() {
    if (this.data.addingMyManual) return

    const userId = this.getLoginUserId()
    if (!userId) {
      wx.showToast({ title: '请先登录后再收藏说明书', icon: 'none' })
      return
    }

    const manual = this.data.manual || {}
    const targetManualId = manual.manualId || manual.id || ''
    if (!targetManualId) {
      wx.showToast({ title: '无法获取说明书ID', icon: 'none' })
      return
    }

    const doRemove = (myManualId) => {
      if (!myManualId) {
        wx.hideLoading()
        this.setData({ addingMyManual: false })
        wx.showToast({ title: '未找到收藏记录', icon: 'none' })
        return
      }
      request({
        url: `/api/my-manuals/${encodeURIComponent(myManualId)}?userId=${encodeURIComponent(userId)}`,
        method: 'DELETE'
      })
        .then(() => {
          wx.showToast({ title: '已取消收藏', icon: 'success' })
          this.setData({
            manualCollected: false,
            collectedMyManualId: '',
            type: this.data.type === 'mine' ? 'platform' : this.data.type
          })
          this.refreshPrevManualList(userId)
        })
        .catch(() => {
          wx.showToast({ title: '取消收藏失败', icon: 'none' })
        })
        .finally(() => {
          wx.hideLoading()
          this.setData({ addingMyManual: false })
        })
    }

    const doAdd = () => {
      request({ url: '/api/my-manuals', method: 'POST', data: { userId, manualId: targetManualId } })
        .then((saved) => {
          wx.showToast({ title: '已收藏', icon: 'success' })
          this.setData({
            manualCollected: true,
            collectedMyManualId: (saved && saved.id) || '',
            type: this.data.type
          })
          this.refreshPrevManualList(userId)
        })
        .catch((err) => {
          if (err && err.statusCode === 409) {
            this.loadCollectState()
            wx.showToast({ title: '已在我的说明书中', icon: 'none' })
          } else {
            wx.showToast({ title: '收藏失败', icon: 'none' })
          }
        })
        .finally(() => {
          wx.hideLoading()
          this.setData({ addingMyManual: false })
        })
    }

    this.setData({ addingMyManual: true })
    if (this.data.manualCollected) {
      wx.showLoading({ title: '取消中...' })
      if (this.data.collectedMyManualId) {
        doRemove(this.data.collectedMyManualId)
        return
      }
      request({ url: `/api/my-manuals?userId=${encodeURIComponent(userId)}` })
        .then((list) => {
          const rows = Array.isArray(list) ? list : (Array.isArray(list && list.items) ? list.items : [])
          const found = rows.find((item) => item && (
            (manual.id && item.id === manual.id) || (item.manualId && item.manualId === targetManualId)
          ))
          doRemove(found ? (found.id || '') : '')
        })
        .catch(() => doRemove(''))
      return
    }

    wx.showLoading({ title: '收藏中...' })
    doAdd()
  },

  refreshPrevManualList(userId) {
    const pages = getCurrentPages()
    const prevPage = pages.length > 1 ? pages[pages.length - 2] : null
    if (prevPage && typeof prevPage.fetchManuals === 'function') {
      prevPage.fetchManuals(userId)
    }
  },

  onCommentTap() {
    const userId = this.getLoginUserId()
    if (!userId) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    const target = this.getTargetInfo()
    if (!target.targetId) {
      wx.showToast({ title: '说明书信息未加载完成', icon: 'none' })
      return
    }

    wx.showModal({
      title: '发表评论',
      editable: true,
      placeholderText: '写下你的看法...',
      success: (res) => {
        const content = (res.content || '').trim()
        if (!res.confirm || !content) return
        const profile = this.getLocalUserProfile()
        request({
          url: '/api/interactions/comments',
          method: 'POST',
          data: {
            userId,
            targetType: target.targetType,
            targetId: target.targetId,
            content,
            fromUserName: profile.name,
            fromAvatar: profile.avatarUrl
          }
        })
          .then(() => this.loadComments())
          .catch(() => wx.showToast({ title: '评论失败', icon: 'none' }))
      }
    })
  },

  onCommentLikeTap(e) {
    const commentId = e.currentTarget.dataset.id
    const userId = this.getLoginUserId()
    if (!userId) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    const profile = this.getLocalUserProfile()
    request({
      url: `/api/interactions/comments/${commentId}/likes/toggle`,
      method: 'POST',
      data: {
        userId,
        fromUserName: profile.name,
        fromAvatar: profile.avatarUrl
      }
    })
      .then(() => this.loadComments())
      .catch(() => wx.showToast({ title: '操作失败', icon: 'none' }))
  },

  onReplyTap(e) {
    const commentId = e.currentTarget.dataset.id
    const userId = this.getLoginUserId()
    if (!userId) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    wx.showModal({
      title: '回复评论',
      editable: true,
      placeholderText: '输入回复内容',
      success: (res) => {
        const content = (res.content || '').trim()
        if (!res.confirm || !content) return
        const profile = this.getLocalUserProfile()
        request({
          url: `/api/interactions/comments/${commentId}/replies`,
          method: 'POST',
          data: {
            userId,
            content,
            fromUserName: profile.name,
            fromAvatar: profile.avatarUrl
          }
        })
          .then(() => this.loadComments())
          .catch(() => wx.showToast({ title: '回复失败', icon: 'none' }))
      }
    })
  }
})
