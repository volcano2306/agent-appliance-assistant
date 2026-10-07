const app = getApp()
const { request } = require('../../utils/request')

Page({
  data: {
    statusBarHeight: 44,
    navHeight: 88,
    scrollHeight: 600,
    tabBarHeight: 32,

    // 平台说明书列表
    manuals: []
  },

  onLoad() {
    const sysInfo = wx.getSystemInfoSync()
    const statusBarHeight = sysInfo.statusBarHeight || 44
    const navHeight = statusBarHeight + 44
    const windowHeight = sysInfo.windowHeight || 600
    const scrollHeight = windowHeight - navHeight
    const tabBarBarPx = Math.round((64 / 750) * sysInfo.windowWidth)
    const safeAreaBottom = sysInfo.screenHeight - (sysInfo.safeArea ? sysInfo.safeArea.bottom : sysInfo.screenHeight)
    const tabBarHeight = tabBarBarPx + safeAreaBottom

    this.setData({
      statusBarHeight,
      navHeight,
      scrollHeight,
      tabBarHeight
    })

    // 加载平台相机说明书
    this.fetchCameraManuals()
  },

  // 从后端获取平台相机说明书
  fetchCameraManuals() {
    request({ url: '/api/manuals?categoryCode=camera' })
      .then(data => {
        const baseUrl = getApp().globalData.baseUrl
        const manuals = data.map(item => {
          // imageUrl 可能是:
          //   /api/files/images/...  (本地服务, 需要拼接 baseUrl)
          //   https://...            (OSS 公网 URL, 直接使用)
          //   空                     (无封面图)
          let imageUrl = item.imageUrl || ''
          if (imageUrl.startsWith('/')) imageUrl = baseUrl + imageUrl

          let pdfUrl = item.pdfUrl || ''
          if (pdfUrl.startsWith('/')) pdfUrl = baseUrl + pdfUrl

          return {
            id: item.id,
            name: item.productName || item.modelName || '未命名',
            image: imageUrl,
            brandName: item.brandName || '',
            modelName: item.modelName || '',
            categoryCode: item.categoryCode || '',
            pdfUrl,
            productId: item.productId || ''
          }
        })
        this.setData({ manuals })
      })
      .catch(err => {
        console.warn('获取相机说明书失败', err)
        wx.showToast({ title: '加载失败', icon: 'none' })
      })
  },

  // 返回上一页
  onBackTap() {
    wx.navigateBack()
  },

  // 点击说明书卡片
  onManualTap(e) {
    const item = e.currentTarget.dataset.item
    wx.navigateTo({
      url: `/pages/appliance/appliance?id=${item.id}&type=platform`
    })
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 0 })
    }
  }
})
