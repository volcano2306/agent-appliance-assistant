const app = getApp()
const { request } = require('../../utils/request')

Page({
  data: {
    statusBarHeight: 44,
    navHeight: 88,
    scrollHeight: 600,
    previewHeight: 400,   // 80% 窗口高度，onLoad 时动态计算

    // 说明书详情
    manual: null,
    type: 'platform', // platform 或 my

    // PDF 页面预览
    pdfUrl: '',
    pageImages: [],    // PDF 逐页图片 OSS URL
    currentPage: 1,
    totalPages: 0
  },

  onLoad(options) {
    const sysInfo = wx.getSystemInfoSync()
    const statusBarHeight = sysInfo.statusBarHeight || 44
    const navHeight = statusBarHeight + 44
    const windowHeight = sysInfo.windowHeight || 600
    const scrollHeight = windowHeight - navHeight
    // PDF 预览区占窗口高度的80%
    const previewHeight = Math.floor(windowHeight * 0.8)

    this.setData({
      statusBarHeight,
      navHeight,
      scrollHeight,
      previewHeight,
      type: options.type || 'platform'
    })

    // 加载说明书详情
    if (options.id) {
      this.fetchManualDetail(options.id, options.type)
    }
  },

  // 获取说明书详情
  fetchManualDetail(id, type) {
    const url = type === 'platform' 
      ? `/api/manuals/${id}` 
      : `/api/my-manuals/${id}`
    
    request({ url })
      .then(data => {
        // pdfUrl 现已是 OSS 公网 URL，直接使用
        const pdfUrl = data.pdfUrl || ''
        const pageImages = data.pageImages || []

        this.setData({
          manual: data,
          pdfUrl,
          pageImages,
          totalPages: pageImages.length || (data.pages || 0)
        })
      })
      .catch(err => {
        console.warn('获取说明书详情失败', err)
        wx.showToast({ title: '加载失败', icon: 'none' })
      })
  },

  // 返回
  onBackTap() {
    wx.navigateBack()
  },

  // 下载PDF
  onDownloadTap() {
    if (!this.data.pdfUrl) {
      wx.showToast({ title: 'PDF文件不存在', icon: 'none' })
      return
    }
    
    wx.showLoading({ title: '下载中...' })
    wx.downloadFile({
      url: this.data.pdfUrl,
      success: (res) => {
        wx.hideLoading()
        if (res.statusCode === 200) {
          wx.openDocument({
            filePath: res.tempFilePath,
            showMenu: true,
            success: () => {
              console.log('打开文档成功')
            }
          })
        }
      },
      fail: () => {
        wx.hideLoading()
        wx.showToast({ title: '下载失败', icon: 'none' })
      }
    })
  },

  // 添加到我的说明书
  onAddToMyTap() {
    if (this.data.type !== 'platform') {
      wx.showToast({ title: '已在我的说明书中', icon: 'none' })
      return
    }

    app.onLoginReady(userId => {
      request({
        url: '/api/my-manuals',
        method: 'POST',
        data: {
          userId: userId,
          manualId: this.data.manual.id
        }
      })
        .then(() => {
          wx.showToast({ title: '添加成功', icon: 'success' })
        })
        .catch(err => {
          console.warn('添加失败', err)
          wx.showToast({ title: '添加失败', icon: 'none' })
        })
    })
  }
})
