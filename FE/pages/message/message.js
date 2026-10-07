const app = getApp()
const { request } = require('../../utils/request')

function interactionReadKey(userId, type) {
  return `interaction_read_${type}_${userId}`
}

function countNewerThan(list, timestamp) {
  const items = Array.isArray(list) ? list : []
  return items.filter(item => Number(item && item.timestamp || 0) > timestamp).length
}

Page({
  data: {
    statusBarHeight: 44,
    navHeight: 88,
    scrollHeight: 600,
    tabBarHeight: 32,
    isMuted: false,

    messages: [],
    likesUnread: 0,
    commentsUnread: 0
  },

  onLoad() {
    const sysInfo = wx.getSystemInfoSync();
    const statusBarHeight = sysInfo.statusBarHeight || 44;
    const navHeight = statusBarHeight + 44;
    const windowHeight = sysInfo.windowHeight || 600;
    const scrollHeight = windowHeight - navHeight;
    const tabBarBarPx = Math.round(64 / 750 * sysInfo.windowWidth);
    const safeAreaBottom = sysInfo.screenHeight - (sysInfo.safeArea ? sysInfo.safeArea.bottom : sysInfo.screenHeight);
    const tabBarHeight = tabBarBarPx + safeAreaBottom;

    const isMuted = wx.getStorageSync('msg_muted') === true;
    this.setData({
      statusBarHeight,
      navHeight,
      scrollHeight,
      tabBarHeight,
      isMuted
    });

    app.onLoginReady(() => {
      this.loadMessages();
    });
    this.loadMessages();
  },

  /**
   * 时间格式化：
   * - 当日：显示 HH:MM
   * - 当年：显示 MM-DD
   * - 非当年：显示 YYYY-MM-DD
   */
  formatTime(timestamp) {
    const date = new Date(timestamp);
    const now = new Date();

    const pad = n => (n < 10 ? '0' + n : '' + n);

    if (
      date.getFullYear() === now.getFullYear() &&
      date.getMonth() === now.getMonth() &&
      date.getDate() === now.getDate()
    ) {
      // 当日
      return pad(date.getHours()) + ':' + pad(date.getMinutes());
    } else if (date.getFullYear() === now.getFullYear()) {
      // 当年
      return pad(date.getMonth() + 1) + '-' + pad(date.getDate());
    } else {
      // 非当年
      return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate());
    }
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 2 });
    }
    this.loadMessages();
  },

  resolveUserId() {
    if (typeof app.resolveUserId === 'function') {
      return app.resolveUserId() || '';
    }
    return '';
  },

  loadMessages() {
    const userId = this.resolveUserId();
    if (!userId) {
      this.setData({ messages: [], likesUnread: 0, commentsUnread: 0 });
      return;
    }

    request({
      url: `/api/messages/summary?userId=${encodeURIComponent(userId)}`
    })
      .then(resp => {
        const list = Array.isArray(resp)
          ? resp
          : (resp && Array.isArray(resp.items) ? resp.items : []);
        const messages = list.map(item => this.normalizeSummary(item));
        const withMute = messages.map(m => {
          const muted = wx.getStorageSync('mute_' + m.id) === true;
          return Object.assign({}, m, { muted });
        });
        this.setData({ messages: withMute });
      })
      .catch(() => {
        this.setData({ messages: [] });
      });
    this.loadInteractionBadges(userId);
  },

  loadInteractionBadges(userId) {
    Promise.all([
      request({ url: `/api/interactions/received/likes?userId=${encodeURIComponent(userId)}` }).catch(() => []),
      request({ url: `/api/interactions/received/comments?userId=${encodeURIComponent(userId)}` }).catch(() => [])
    ]).then(([likes, comments]) => {
      const likesReadAt = Number(wx.getStorageSync(interactionReadKey(userId, 'likes')) || 0)
      const commentsReadAt = Number(wx.getStorageSync(interactionReadKey(userId, 'comments')) || 0)
      this.setData({
        likesUnread: countNewerThan(likes, likesReadAt),
        commentsUnread: countNewerThan(comments, commentsReadAt)
      })
      this.refreshTabBadge()
    })
  },

  refreshTabBadge() {
    if (typeof this.getTabBar === 'function' && this.getTabBar() && this.getTabBar().refreshBadge) {
      this.getTabBar().refreshBadge()
    }
  },

  normalizeSummary(item) {
    const type = item && item.type ? item.type : 'system';
    const timestamp = Number(item && item.timestamp ? item.timestamp : 0);
    const subtitle = (item && (item.subtitle || item.preview)) || '';
    const icon = this.normalizeAssetUrl((item && item.icon) || this.getIconByType(type));
    return {
      id: item && item.id ? item.id : type,
      type,
      title: (item && item.title) || (type === 'brand' ? '品牌消息' : '系统消息'),
      subtitle,
      unread: Number((item && item.unread) || 0),
      timestamp,
      timeDisplay: this.formatTime(timestamp),
      icon,
      preview: subtitle
    };
  },

  normalizeAssetUrl(url) {
    if (!url) return '';
    if (/^https?:\/\//i.test(url) || url.startsWith('wxfile://') || url.startsWith('cloud://')) {
      return url;
    }
    if (url.startsWith('/api/')) {
      return (app.globalData.baseUrl || '').replace(/\/$/, '') + url;
    }
    return url;
  },

  getIconByType(type) {
    if (type === 'brand') {
      return '/images/message/MCPNG/pic_line.png';
    }
    return '/images/message/MCPNG/notification_newdot_line.png';
  },

  onQuickTap(e) {
    const type = e.currentTarget.dataset.type;
    if (type === 'likes') {
      wx.navigateTo({ url: '/pages/likes/likes' });
    } else {
      wx.navigateTo({ url: '/pages/comments/comments' });
    }
  },

  onMsgTap(e) {
    const dataset = e.currentTarget.dataset || {};
    const item = {
      id: dataset.id,
      type: dataset.type,
      title: dataset.title,
      subtitle: dataset.subtitle,
      icon: dataset.icon,
      preview: dataset.preview
    };
    if (!item.id) {
      return;
    }

    const userId = this.resolveUserId();
    const updateLocal = () => {
      const messages = this.data.messages.map(m =>
        m.id === item.id ? Object.assign({}, m, { unread: 0 }) : m
      );
      this.setData({ messages });
      this.refreshTabBadge();
    };

    if (userId) {
      request({
        url: '/api/messages/read',
        method: 'POST',
        data: {
          userId,
          type: item.type,
          threadId: item.id
        }
      }).finally(() => {
        updateLocal();
      });
    } else {
      updateLocal();
    }

    const chatType = item.type || 'system';
    const query = `chatId=${encodeURIComponent(item.id)}`
      + `&chatName=${encodeURIComponent(item.title || '')}`
      + `&chatType=${encodeURIComponent(chatType)}`
      + `&preview=${encodeURIComponent(item.preview || item.subtitle || '')}`
      + `&icon=${encodeURIComponent(item.icon || '')}`;
    wx.navigateTo({
      url: '/pages/msgchat/msgchat?' + query
    });
  },

  onMuteTap() {
    const newMuted = !this.data.isMuted;
    wx.setStorageSync('msg_muted', newMuted);
    this.setData({ isMuted: newMuted });
    wx.showToast({
      title: newMuted ? '已开启免打扰' : '已关闭免打扰',
      icon: 'none',
      duration: 1500
    });
  },

  onClearTap() {
    const userId = this.resolveUserId();
    wx.showModal({
      title: '全部已读',
      content: '确定将所有消息标记为已读？',
      success: (res) => {
        if (res.confirm) {
          if (userId) {
            request({
              url: '/api/messages/read',
              method: 'POST',
              data: {
                userId,
                type: 'all'
              }
            }).catch(() => {});
          }
          const messages = this.data.messages.map(m =>
            Object.assign({}, m, { unread: 0 })
          );
          if (userId) {
            const now = Date.now();
            wx.setStorageSync(interactionReadKey(userId, 'likes'), now);
            wx.setStorageSync(interactionReadKey(userId, 'comments'), now);
          }
          this.setData({ messages, likesUnread: 0, commentsUnread: 0 });
          this.refreshTabBadge();
          wx.showToast({ title: '已全部已读', icon: 'success', duration: 1000 });
        }
      }
    });
  },

  onQuarterTap() {
    wx.navigateTo({ url: '/pages/ai/ai' });
  },

  onSearchTap() {
    wx.navigateTo({ url: '/pages/search/search' });
  }
});
