const app = getApp()
const { request } = require('../../utils/request')

const ALL_LABEL = '全部'
const CAMERA_LABEL = '相机'
const APPLIANCE_LABEL = '家电'
const ALL_SECOND_LEVEL_LABEL = '全部设备类型'
const ALL_BRAND_LABEL = '全部品牌'
const APPLIANCE_SECOND_ORDER = ['洗衣机', '生活电器', '空调', '冰箱', '电视', '厨房电器', '通用家电']

function normalizeFirstLevel(rawCategory) {
  const value = (rawCategory || '').toLowerCase()
  if (value.includes('camera') || value.includes('相机')) return CAMERA_LABEL
  return APPLIANCE_LABEL
}

function inferSecondLevel(firstLevel, name, model, rawCategory) {
  const category = (rawCategory || '').trim()
  if (firstLevel !== CAMERA_LABEL && category) return category
  const text = `${name || ''} ${model || ''}`.toLowerCase()
  if (firstLevel === CAMERA_LABEL) {
    if (/rb67|rz67|645|mamiya|中画幅/.test(text)) return '中画幅相机'
    if (/vivicam|digital|数码|dc/.test(text)) return '数码相机'
    if (/lens|镜头/.test(text)) return '镜头配件'
    return '机身设备'
  }
  if (/空调|kfr|air/.test(text)) return '空调'
  if (/洗衣|xqg/.test(text)) return '洗衣机'
  if (/冰箱|bcd/.test(text)) return '冰箱'
  if (/电视|tv/.test(text)) return '电视'
  if (/电饭煲|豆浆机|厨房/.test(text)) return '厨房电器'
  if (/净化器|风扇|吸尘|小家电/.test(text)) return '生活电器'
  return '通用家电'
}

function sortByOrder(list, order) {
  return (list || []).slice().sort((a, b) => {
    const ai = order.indexOf(a)
    const bi = order.indexOf(b)
    if (ai !== -1 || bi !== -1) return (ai === -1 ? 999 : ai) - (bi === -1 ? 999 : bi)
    return String(a || '').localeCompare(String(b || ''))
  })
}

Page({
  data: {
    statusBarHeight: 44,
    navHeight: 88,
    scrollHeight: 600,
    contentHeight: 548,
    tabBarHeight: 32,

    firstLevelList: [ALL_LABEL],
    selectedFirstLevel: ALL_LABEL,

    cameraSecondLevelList: [ALL_SECOND_LEVEL_LABEL],
    selectedCameraSecondLevel: ALL_SECOND_LEVEL_LABEL,

    brandList: [ALL_BRAND_LABEL],
    selectedBrand: ALL_BRAND_LABEL,

    currentPathText: `${ALL_LABEL} / ${ALL_BRAND_LABEL}`,
    allManuals: [],
    manuals: [],
    loading: true
  },

  onLoad() {
    const sysInfo = wx.getSystemInfoSync()
    const statusBarHeight = sysInfo.statusBarHeight || 44
    const navHeight = statusBarHeight + 44
    const windowHeight = sysInfo.windowHeight || 600
    const scrollHeight = windowHeight - navHeight
    const pathBarPx = Math.round((76 / 750) * sysInfo.windowWidth)
    const contentHeight = Math.max(260, scrollHeight - pathBarPx)
    const tabBarBarPx = Math.round((64 / 750) * sysInfo.windowWidth)
    const safeAreaBottom = sysInfo.screenHeight - (sysInfo.safeArea ? sysInfo.safeArea.bottom : sysInfo.screenHeight)
    const tabBarHeight = tabBarBarPx + safeAreaBottom
    this.setData({ statusBarHeight, navHeight, scrollHeight, contentHeight, tabBarHeight })
    this.fetchManuals()
  },

  onShow() {
    this.fetchManuals()
  },

  fetchManuals() {
    const baseUrl = app.globalData.baseUrl || ''
    this.setData({ loading: true })
    request({ url: '/api/manuals' })
      .then((data) => {
        if (!Array.isArray(data)) {
          this.setData({ loading: false })
          return
        }

        const manuals = data.map((item) => {
          let image = item.imageUrl || ''
          if (image.startsWith('/')) image = baseUrl + image
          const name = item.productName || item.modelName || '未命名'
          const model = item.modelName || ''
          const firstLevel = normalizeFirstLevel((item.categoryCode || '').trim())
          const secondLevel = inferSecondLevel(firstLevel, name, model, item.categoryCode)
          return {
            id: item.id,
            name,
            model,
            image,
            brandName: (item.brandName || '').trim() || '未知品牌',
            firstLevel,
            secondLevel
          }
        })

        const applianceSecondSet = new Set()
        const cameraSecondSet = new Set()
        manuals.forEach((item) => {
          if (item.firstLevel === CAMERA_LABEL) {
            cameraSecondSet.add(item.secondLevel)
          } else {
            applianceSecondSet.add(item.secondLevel)
          }
        })

        const sidebars = [ALL_LABEL]
        sidebars.push(...sortByOrder(Array.from(applianceSecondSet), APPLIANCE_SECOND_ORDER))
        if (cameraSecondSet.size > 0) sidebars.push(CAMERA_LABEL)

        const cameraSecondLevelList = [ALL_SECOND_LEVEL_LABEL, ...Array.from(cameraSecondSet).sort()]

        this.setData(
          {
            allManuals: manuals,
            firstLevelList: sidebars,
            cameraSecondLevelList,
            loading: false
          },
          () => {
            const selectedFirst = sidebars.includes(this.data.selectedFirstLevel) ? this.data.selectedFirstLevel : ALL_LABEL
            this.setData(
              {
                selectedFirstLevel: selectedFirst,
                selectedCameraSecondLevel: ALL_SECOND_LEVEL_LABEL,
                selectedBrand: ALL_BRAND_LABEL
              },
              () => {
                this.updateBrandOptions()
                this.applyFilter()
              }
            )
          }
        )
      })
      .catch(() => this.setData({ loading: false }))
  },

  onQuarterTap() {
    wx.navigateTo({ url: '/pages/ai/ai' })
  },

  onBackTap() {
    wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/index/index' }) })
  },

  onSearchTap() {
    wx.navigateTo({ url: '/pages/search/search' })
  },

  onFirstLevelTap(e) {
    const level = e.currentTarget.dataset.level || ALL_LABEL
    this.setData(
      {
        selectedFirstLevel: level,
        selectedCameraSecondLevel: ALL_SECOND_LEVEL_LABEL,
        selectedBrand: ALL_BRAND_LABEL
      },
      () => {
        this.updateBrandOptions()
        this.applyFilter()
      }
    )
  },

  onCameraSecondLevelTap(e) {
    if (this.data.selectedFirstLevel !== CAMERA_LABEL) return
    const second = e.currentTarget.dataset.level || ALL_SECOND_LEVEL_LABEL
    this.setData(
      {
        selectedCameraSecondLevel: second,
        selectedBrand: ALL_BRAND_LABEL
      },
      () => {
        this.updateBrandOptions()
        this.applyFilter()
      }
    )
  },

  onBrandTap(e) {
    const brand = e.currentTarget.dataset.brand || ALL_BRAND_LABEL
    this.setData({ selectedBrand: brand }, () => this.applyFilter())
  },

  updateBrandOptions() {
    const { selectedFirstLevel, selectedCameraSecondLevel, allManuals } = this.data
    const set = new Set()
    ;(allManuals || []).forEach((item) => {
      if (!item.brandName || item.brandName === '未知品牌') return
      if (selectedFirstLevel === ALL_LABEL) return
      if (selectedFirstLevel === CAMERA_LABEL) {
        if (item.firstLevel !== CAMERA_LABEL) return
        if (selectedCameraSecondLevel !== ALL_SECOND_LEVEL_LABEL && item.secondLevel !== selectedCameraSecondLevel) return
        set.add(item.brandName)
        return
      }
      if (item.firstLevel === APPLIANCE_LABEL && item.secondLevel === selectedFirstLevel) {
        set.add(item.brandName)
      }
    })
    this.setData({ brandList: [ALL_BRAND_LABEL, ...Array.from(set).sort()] })
  },

  applyFilter() {
    const { allManuals, selectedFirstLevel, selectedCameraSecondLevel, selectedBrand } = this.data
    const filtered = (allManuals || []).filter((item) => {
      if (selectedFirstLevel === ALL_LABEL) {
        if (item.firstLevel === CAMERA_LABEL) return false
      } else if (selectedFirstLevel === CAMERA_LABEL) {
        if (item.firstLevel !== CAMERA_LABEL) return false
        if (selectedCameraSecondLevel !== ALL_SECOND_LEVEL_LABEL && item.secondLevel !== selectedCameraSecondLevel) {
          return false
        }
      } else if (!(item.firstLevel === APPLIANCE_LABEL && item.secondLevel === selectedFirstLevel)) {
        return false
      }

      if (selectedBrand !== ALL_BRAND_LABEL && item.brandName !== selectedBrand) return false
      return true
    })

    const currentPathText = selectedFirstLevel === CAMERA_LABEL
      ? `${selectedFirstLevel} / ${selectedCameraSecondLevel} / ${selectedBrand}`
      : `${selectedFirstLevel} / ${selectedBrand}`

    this.setData({ manuals: filtered, currentPathText })
  },

  onManualTap(e) {
    const item = e.currentTarget.dataset.item
    wx.navigateTo({ url: `/pages/appliance/appliance?id=${item.id}&type=platform` })
  }
})
