const app = getApp()
const { request } = require('../../utils/request')

Page({
  data: {
    statusBarHeight: 44,
    navHeight: 88,

    nickname: '',
    phone: '',
    avatarUrl: '',

    // 弹窗控制
    showNicknamePopup: false,
    showPhonePopup: false,
    showDeactivatePopup: false,

    // 弹窗编辑中间值
    editNickname: '',
    editPhone: ''
  },

  onLoad() {
    const sysInfo = wx.getSystemInfoSync();
    const statusBarHeight = sysInfo.statusBarHeight || 44;
    const navHeight = statusBarHeight + 44;
    this.setData({ statusBarHeight, navHeight });

    // 从本地存储读取用户信息
    const userInfo = wx.getStorageSync('userInfo') || {};
    this.setData({
      nickname: userInfo.name || '',
      phone: userInfo.phone || '',
      avatarUrl: userInfo.avatarUrl || ''
    });
  },

  onBackTap() {
    wx.navigateBack();
  },

  onAvatarCameraTap() {
    wx.chooseImage({
      count: 1,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const tempPath = res.tempFilePaths && res.tempFilePaths[0];
        if (!tempPath) {
          return;
        }
        this.saveAvatar(tempPath);
      },
      fail: (err) => {
        if (err && err.errMsg && err.errMsg.includes('cancel')) {
          return;
        }
        wx.showToast({ title: '上传失败', icon: 'none' });
      }
    });
  },

  saveAvatar(tempFilePath) {
    wx.saveFile({
      tempFilePath,
      success: (res) => {
        const avatarUrl = res.savedFilePath || tempFilePath;
        this.persistAvatar(avatarUrl);
      },
      fail: () => {
        this.persistAvatar(tempFilePath);
      }
    });
  },

  persistAvatar(avatarUrl) {
    const userInfo = wx.getStorageSync('userInfo') || {};
    userInfo.avatarUrl = avatarUrl;
    wx.setStorageSync('userInfo', userInfo);
    this.setData({ avatarUrl });
    wx.showToast({ title: '头像已更新', icon: 'success' });
  },

  // -------- 昵称 --------
  onNicknameTap() {
    this.setData({
      editNickname: this.data.nickname,
      showNicknamePopup: true
    });
  },

  onNicknameInput(e) {
    this.setData({ editNickname: e.detail.value });
  },

  onNicknameConfirm() {
    const val = (this.data.editNickname || '').trim();
    if (!val) {
      wx.showToast({ title: '昵称不能为空', icon: 'none' });
      return;
    }
    const userInfo = wx.getStorageSync('userInfo') || {};
    userInfo.name = val;
    wx.setStorageSync('userInfo', userInfo);
    this.setData({ nickname: val, showNicknamePopup: false });
    wx.showToast({ title: '昵称已更新', icon: 'success' });
  },

  // -------- 手机 --------
  onPhoneTap() {
    this.setData({
      editPhone: this.data.phone,
      showPhonePopup: true
    });
  },

  onPhoneInput(e) {
    this.setData({ editPhone: e.detail.value });
  },

  onPhoneConfirm() {
    const val = (this.data.editPhone || '').trim();
    if (!/^1[3-9]\d{9}$/.test(val)) {
      wx.showToast({ title: '请输入有效手机号', icon: 'none' });
      return;
    }
    const userInfo = wx.getStorageSync('userInfo') || {};
    userInfo.phone = val;
    wx.setStorageSync('userInfo', userInfo);
    this.setData({ phone: val, showPhonePopup: false });
    wx.showToast({ title: '手机已更新', icon: 'success' });
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
        userId: app.globalData.userId || ''
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

  // -------- 注销账号 --------
  onDeactivateTap() {
    this.setData({ showDeactivatePopup: true });
  },

  onDeactivateConfirm() {
    // 清除本地数据并退出到首页
    wx.removeStorageSync('userInfo');
    app.logout();
    this.setData({ showDeactivatePopup: false });
    wx.showToast({ title: '账号已注销', icon: 'success' });
    setTimeout(() => {
      wx.switchTab({ url: '/pages/profile/profile' });
    }, 1200);
  },

  // -------- 退出登录 --------
  onLogoutTap() {
    wx.showModal({
      title: '退出登录',
      content: '确认退出当前账号？',
      confirmText: '退出',
      confirmColor: '#E9AE0E',
      success: res => {
        if (res.confirm) {
          wx.removeStorageSync('userInfo');
          app.logout();
          wx.switchTab({ url: '/pages/profile/profile' });
        }
      }
    });
  },

  // -------- 通用弹窗关闭 --------
  onClosePopup() {
    this.setData({
      showNicknamePopup: false,
      showPhonePopup: false,
      showDeactivatePopup: false
    });
  },

  // 点击遮罩关闭弹窗
  onOverlayTap() {
    this.onClosePopup();
  }
});
