const { loadSettings } = require('./utils/settings')

function normalizeUserId(value) {
  const text = (value || '').toString().trim()
  if (!text) return ''
  const lowered = text.toLowerCase()
  if (lowered === 'undefined' || lowered === 'null' || lowered === '[object object]') {
    return ''
  }
  return text
}

App({
  loginWithCode() {
    return new Promise((resolve) => {
      wx.login({
        success: (res) => {
          if (!res.code) {
            this.logout()
            resolve(false)
            return
          }
          wx.request({
            url: this.globalData.baseUrl + '/api/auth/login',
            method: 'POST',
            data: { code: res.code },
            header: { 'Content-Type': 'application/json' },
            success: (result) => {
              const uid = normalizeUserId(result && result.data && result.data.userId)
              if (result.statusCode === 200 && uid) {
                wx.setStorageSync('sms_join_at', (result.data && result.data.joinAt) || '')
                this.completeLogin(uid, {
                  openid: result.data.openid,
                  points: result.data.points,
                  joinAt: result.data.joinAt
                })
                resolve(true)
                return
              }
              this.logout()
              resolve(false)
            },
            fail: (err) => {
              console.warn('登录请求失败', err)
              this.logout()
              resolve(false)
            }
          })
        },
        fail: () => {
          this.logout()
          resolve(false)
        }
      })
    })
  },

  completeLogin(uid, extra = {}) {
    const normalizedUid = normalizeUserId(uid)
    if (!normalizedUid) {
      this.logout()
      return
    }
    this.globalData.userId = normalizedUid
    wx.setStorageSync('sms_user_id', normalizedUid)
    if (extra.openid !== undefined) this.globalData.openid = extra.openid
    if (extra.points !== undefined) this.globalData.points = extra.points
    if (extra.joinAt !== undefined) this.globalData.joinAt = extra.joinAt
    this.globalData.loginReady = true
    this.globalData.loginCallbacks.forEach((cb) => cb(normalizedUid))
    this.globalData.loginCallbacks = []
  },

  logout() {
    wx.removeStorageSync('sms_user_id')
    wx.removeStorageSync('sms_join_at')
    wx.removeStorageSync('userInfo')
    this.globalData.userId = null
    this.globalData.openid = null
    this.globalData.points = 0
    this.globalData.joinAt = null
    this.globalData.loginReady = false
    this.globalData.loginCallbacks = []
  },

  onLaunch() {
    wx.loadFontFace({
      global: true,
      family: 'MingCute',
      source: 'url("https://cdn.jsdelivr.net/npm/mingcute_icon@2.9.2/font/MingCute.woff")',
      success: () => console.log('MingCute font loaded'),
      fail: (err) => console.warn('MingCute font load failed', err)
    })

    this.globalData.settings = loadSettings()
    const cachedUserId = normalizeUserId(wx.getStorageSync('sms_user_id'))
    const cachedJoinAt = wx.getStorageSync('sms_join_at')
    if (cachedUserId) {
      this.completeLogin(cachedUserId, { joinAt: cachedJoinAt || null })
      return
    }

    wx.removeStorageSync('sms_user_id')
    wx.removeStorageSync('sms_join_at')
    this.globalData.userId = null
    this.globalData.loginReady = false
  },

  onLoginReady(cb) {
    if (this.globalData.loginReady) {
      cb(this.globalData.userId)
      return
    }
    this.globalData.loginCallbacks.push(cb)
  },

  resolveUserId() {
    const cached = normalizeUserId(wx.getStorageSync('sms_user_id'))
    const globalId = normalizeUserId(this.globalData && this.globalData.userId)
    const uid = cached || globalId || ''
    if (uid) {
      this.globalData.userId = uid
      this.globalData.loginReady = true
      if (cached !== uid) {
        wx.setStorageSync('sms_user_id', uid)
      }
      return uid
    }
    this.globalData.userId = null
    this.globalData.loginReady = false
    return ''
  },

  globalData: {
    baseUrl: 'http://localhost:18083',
    userInfo: null,
    userId: null,
    openid: null,
    points: 0,
    joinAt: null,
    loginReady: false,
    loginCallbacks: [],
    settings: loadSettings()
  }
})
