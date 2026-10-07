const app = getApp()
const { request } = require('../../utils/request')

function resolveLoginUserId() {
  if (typeof app.resolveUserId === 'function') {
    return app.resolveUserId() || ''
  }
  return (app.globalData && app.globalData.userId) || ''
}

Page({
  data: {
    statusBarHeight: 44,
    navHeight: 88,
    scrollHeight: 600,
    tabBarHeight: 32,

    isLoggedIn: false,
    joinDays: 0,
    nickname: '',
    phone: '',
    avatarUrl: '',

    showNicknamePopup: false,
    showPhonePopup: false,
    showDeactivatePopup: false,
    editNickname: '',
    editPhone: ''
  },

  onLoad() {
    const sysInfo = wx.getSystemInfoSync()
    const statusBarHeight = sysInfo.statusBarHeight || 44
    const navHeight = statusBarHeight + 44
    const windowHeight = sysInfo.windowHeight || 600
    const tabBarBarPx = Math.round((64 / 750) * sysInfo.windowWidth)
    const safeAreaBottom = sysInfo.screenHeight - (sysInfo.safeArea ? sysInfo.safeArea.bottom : sysInfo.screenHeight)
    const tabBarHeight = tabBarBarPx + safeAreaBottom
    const scrollHeight = windowHeight - navHeight - tabBarHeight

    this.setData({ statusBarHeight, navHeight, scrollHeight, tabBarHeight })
    this.checkLogin()

    app.onLoginReady(() => {
      this.checkLogin()
    })
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 3 })
    }
    this.checkLogin()
  },

  checkLogin() {
    const userInfo = wx.getStorageSync('userInfo') || {}
    const userId = resolveLoginUserId()

    if (userId) {
      const joinAtRaw = app.globalData.joinAt || wx.getStorageSync('sms_join_at') || userInfo.joinDate
      const joinDate = joinAtRaw ? new Date(joinAtRaw) : new Date()
      const validJoinDate = Number.isNaN(joinDate.getTime()) ? new Date() : joinDate
      const days = Math.max(1, Math.ceil((Date.now() - validJoinDate.getTime()) / (24 * 3600 * 1000)))
      this.setData({
        isLoggedIn: true,
        joinDays: days,
        nickname: userInfo.name || '微信用户',
        phone: userInfo.phone || '',
        avatarUrl: userInfo.avatarUrl || ''
      })
      return
    }

    this.setData({
      isLoggedIn: false,
      joinDays: 0,
      nickname: '',
      phone: '',
      avatarUrl: ''
    })
  },

  onLoginTap() {
    wx.getUserProfile({
      desc: '用于完善用户昵称和头像信息',
      success: (res) => {
        const profile = res.userInfo || {}
        const old = wx.getStorageSync('userInfo') || {}
        const nextUserInfo = {
          ...old,
          name: profile.nickName || old.name || '',
          avatarUrl: profile.avatarUrl || old.avatarUrl || '',
          phone: old.phone || ''
        }
        wx.setStorageSync('userInfo', nextUserInfo)

        if (resolveLoginUserId()) {
          this.checkLogin()
          wx.showToast({ title: '微信授权成功', icon: 'success' })
          return
        }

        app.loginWithCode().then((ok) => {
          this.checkLogin()
          if (ok) {
            wx.showToast({ title: '登录成功', icon: 'success' })
            return
          }
          wx.showToast({ title: '登录失败，请检查网络后重试', icon: 'none' })
        })
      },
      fail: (err) => {
        if (err && err.errMsg && err.errMsg.includes('cancel')) {
          wx.showToast({ title: '已取消授权', icon: 'none' })
          return
        }
        wx.showToast({ title: '授权失败', icon: 'none' })
      }
    })
  },

  onAvatarCameraTap() {
    wx.chooseImage({
      count: 1,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const tempPath = res.tempFilePaths && res.tempFilePaths[0]
        if (!tempPath) return
        this.saveAvatar(tempPath)
      },
      fail: (err) => {
        if (err && err.errMsg && err.errMsg.includes('cancel')) return
        wx.showToast({ title: '上传失败', icon: 'none' })
      }
    })
  },

  saveAvatar(tempFilePath) {
    wx.saveFile({
      tempFilePath,
      success: (res) => {
        const avatarUrl = res.savedFilePath || tempFilePath
        this.persistAvatar(avatarUrl)
      },
      fail: () => {
        this.persistAvatar(tempFilePath)
      }
    })
  },

  persistAvatar(avatarUrl) {
    const userInfo = wx.getStorageSync('userInfo') || {}
    userInfo.avatarUrl = avatarUrl
    wx.setStorageSync('userInfo', userInfo)
    this.setData({ avatarUrl })
    wx.showToast({ title: '头像已更新', icon: 'success' })
  },

  onNicknameTap() {
    this.setData({
      editNickname: this.data.nickname,
      showNicknamePopup: true
    })
  },

  onNicknameInput(e) {
    this.setData({ editNickname: e.detail.value })
  },

  onNicknameConfirm() {
    const val = (this.data.editNickname || '').trim()
    if (!val) {
      wx.showToast({ title: '昵称不能为空', icon: 'none' })
      return
    }
    const userInfo = wx.getStorageSync('userInfo') || {}
    userInfo.name = val
    wx.setStorageSync('userInfo', userInfo)
    this.setData({ nickname: val, showNicknamePopup: false })
    wx.showToast({ title: '昵称已更新', icon: 'success' })
  },

  onPhoneTap() {
    this.setData({
      editPhone: this.data.phone,
      showPhonePopup: true
    })
  },

  onPhoneInput(e) {
    this.setData({ editPhone: e.detail.value })
  },

  onPhoneConfirm() {
    const val = (this.data.editPhone || '').trim()
    if (!/^1[3-9]\d{9}$/.test(val)) {
      wx.showToast({ title: '请输入有效手机号', icon: 'none' })
      return
    }
    const userInfo = wx.getStorageSync('userInfo') || {}
    userInfo.phone = val
    wx.setStorageSync('userInfo', userInfo)
    this.setData({ phone: val, showPhonePopup: false })
    wx.showToast({ title: '手机已更新', icon: 'success' })
  },

  onGetPhoneNumber(e) {
    const detail = e.detail || {}
    if (detail.errMsg !== 'getPhoneNumber:ok') {
      wx.showToast({ title: '手机号授权已取消', icon: 'none' })
      return
    }

    const code = detail.code
    if (!code) {
      wx.showToast({ title: '未获取到授权凭证', icon: 'none' })
      return
    }

    request({
      url: '/api/auth/phone',
      method: 'POST',
      data: {
        code,
        userId: resolveLoginUserId()
      }
    }).then((res) => {
      const phone = (res && res.phone) || ''
      if (!/^1[3-9]\d{9}$/.test(phone)) {
        wx.showToast({ title: '手机号解析失败', icon: 'none' })
        return
      }
      const userInfo = wx.getStorageSync('userInfo') || {}
      userInfo.phone = phone
      wx.setStorageSync('userInfo', userInfo)
      this.setData({ phone, editPhone: phone, showPhonePopup: false })
      wx.showToast({ title: '手机号已授权', icon: 'success' })
    }).catch(() => {
      wx.showToast({ title: '手机号授权接口未开通，可手动填写', icon: 'none' })
    })
  },

  onSettingsTap() {
    wx.navigateTo({ url: '/pages/settings/settings' })
  },

  onDeactivateTap() {
    this.setData({ showDeactivatePopup: true })
  },

  onDeactivateConfirm() {
    wx.removeStorageSync('userInfo')
    app.logout()
    this.checkLogin()
    this.setData({ showDeactivatePopup: false })
    wx.showToast({ title: '账号已注销', icon: 'success' })
  },

  onLogoutTap() {
    wx.showModal({
      title: '退出登录',
      content: '确认退出当前账号？',
      confirmText: '退出',
      confirmColor: '#E9AE0E',
      success: (res) => {
        if (res.confirm) {
          wx.removeStorageSync('userInfo')
          app.logout()
          this.checkLogin()
        }
      }
    })
  },

  onClosePopup() {
    this.setData({
      showNicknamePopup: false,
      showPhonePopup: false,
      showDeactivatePopup: false
    })
  },

  onOverlayTap() {
    this.onClosePopup()
  },

  noPropagation() {},

  onQuarterTap() {
    wx.navigateTo({ url: '/pages/ai/ai' })
  },

  onSearchTap() {
    wx.navigateTo({ url: '/pages/search/search' })
  }
})
