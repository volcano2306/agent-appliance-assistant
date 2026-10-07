const app = getApp()
const { request } = require('../utils/request')

function readKey(userId, type) {
  return `interaction_read_${type}_${userId}`
}

function countNewerThan(list, timestamp) {
  const items = Array.isArray(list) ? list : []
  return items.filter(item => Number(item && item.timestamp || 0) > timestamp).length
}

Component({
  data: {
    selected: 0,
    messageBadge: 0
  },
  lifetimes: {
    attached() {
      this.refreshBadge()
      app.onLoginReady(() => this.refreshBadge())
      this._badgeTimer = setInterval(() => this.refreshBadge(), 30000)
    },
    detached() {
      if (this._badgeTimer) {
        clearInterval(this._badgeTimer)
        this._badgeTimer = null
      }
    }
  },
  pageLifetimes: {
    show() {
      this.refreshBadge()
    }
  },
  methods: {
    refreshBadge() {
      const userId = (typeof app.resolveUserId === 'function' ? app.resolveUserId() : '') || ''
      if (!userId) {
        this.setData({ messageBadge: 0 })
        return
      }
      Promise.all([
        request({ url: `/api/messages/summary?userId=${encodeURIComponent(userId)}` }).catch(() => []),
        request({ url: `/api/interactions/received/likes?userId=${encodeURIComponent(userId)}` }).catch(() => []),
        request({ url: `/api/interactions/received/comments?userId=${encodeURIComponent(userId)}` }).catch(() => [])
      ]).then(([messages, likes, comments]) => {
        const messageUnread = (Array.isArray(messages) ? messages : []).reduce((sum, item) => {
          return sum + Number((item && item.unread) || 0)
        }, 0)
        const likesReadAt = Number(wx.getStorageSync(readKey(userId, 'likes')) || 0)
        const commentsReadAt = Number(wx.getStorageSync(readKey(userId, 'comments')) || 0)
        const total = messageUnread + countNewerThan(likes, likesReadAt) + countNewerThan(comments, commentsReadAt)
        this.setData({ messageBadge: total })
      })
    },
    switchTab(e) {
      const { path, index } = e.currentTarget.dataset;
      wx.switchTab({ url: path });
      this.setData({ selected: parseInt(index) });
    },
    onAddTap() {
      wx.navigateTo({
        url: '/pages/publish/publish'
      });
    }
  }
});
