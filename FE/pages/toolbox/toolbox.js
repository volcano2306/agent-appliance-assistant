const app = getApp();
const { createServiceOrder } = require('../../utils/serviceOrders');

Page({
  data: {
    statusBarHeight: 44,
    navHeight: 88,
    scrollHeight: 600,
    tabBarHeight: 32,
    enterpriseBanner: {
      tag: '企业端',
      title: '开放入驻通道',
      desc: '企业可入驻平台，提供官方售后与配件服务',
      cta: '立即入驻'
    },
    enterpriseServices: [
      {
        id: 'after-sales',
        title: '售后咨询',
        desc: '官方在线客服',
        iconText: '售',
        action: 'officialAfterSales'
      },
      {
        id: 'remote-guide',
        title: '远程指导',
        desc: '视频连线排障',
        iconText: '远',
        action: 'remoteGuideNow'
      },
      {
        id: 'parts-shop',
        title: '配件购买',
        desc: '原厂/适配件',
        iconText: '配',
        action: 'buyParts'
      }
    ],
    remoteGuideCard: {
      title: '找维修师',
      desc: '第三方维修师入驻，一对一视频指导',
      tags: ['专业维修师', '一对一视频', '按次计费', '全品类家电'],
      feeLabel: '收费远程指导',
      cta: '立即预约',
      action: 'bookRemoteGuide'
    },
    afterSalesTools: [
      {
        id: 'my-orders',
        title: '我的工单',
        desc: '查看处理进度',
        action: 'openWorkOrders'
      },
      {
        id: 'warranty-upload',
        title: '保修资料',
        desc: '上传发票/保修卡',
        action: 'uploadWarranty'
      },
      {
        id: 'service-rating',
        title: '服务评价',
        desc: '评价维修体验',
        action: 'serviceRating'
      },
      {
        id: 'maintenance-notice',
        title: '进度提醒',
        desc: '消息中心同步',
        action: 'openServiceNotices'
      }
    ],
    utilityTools: [
      {
        id: 'ai-precheck',
        title: 'AI 故障预检',
        subtitle: '先问再修，减少无效报修',
        action: 'goAiPrecheck'
      },
      {
        id: 'fault-code',
        title: '问题速查',
        subtitle: '按品牌/型号快速检索',
        action: 'faultCodeSearch'
      },
      {
        id: 'care-calendar',
        title: '设备保养日历',
        subtitle: '设置滤芯/清洗提醒',
        action: 'maintenanceCalendar'
      },
      {
        id: 'service-price',
        title: '维修价格参考',
        subtitle: '透明价格区间，防止踩坑',
        action: 'servicePriceReference'
      },
      {
        id: 'service-outlet',
        title: '官方网点查询',
        subtitle: '快速定位品牌服务网点',
        action: 'serviceOutletQuery'
      }
    ]
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

    const utilityTools = (this.data.utilityTools || [])
      .filter(item => item && item.action !== 'servicePriceReference' && item.action !== 'serviceOutletQuery');
    const hasWorkOrders = utilityTools.some(item => item && item.action === 'openWorkOrders');
    if (!hasWorkOrders) {
      utilityTools.push({
        id: 'my-orders',
        title: '我的工单',
        subtitle: '查看处理进度',
        action: 'openWorkOrders'
      });
    }

    this.setData({ statusBarHeight, navHeight, scrollHeight, tabBarHeight, utilityTools });
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 1 });
    }
  },

  onQuarterTap() {
    wx.navigateTo({ url: '/pages/ai/ai' });
  },

  onSearchTap() {
    wx.navigateTo({ url: '/pages/search/search' });
  },

  onEnterpriseJoinTap() {
    wx.showActionSheet({
      itemList: ['品牌官方入驻', '维修团队入驻', '配件供应商入驻'],
      success: (res) => {
        const roleMap = ['品牌官方', '维修团队', '配件供应商'];
        const role = roleMap[res.tapIndex] || '企业';
        wx.showModal({
          title: role + '入驻',
          content: '请准备营业执照、联系人、服务范围信息。当前为内测流程，可先联系运营对接。',
          confirmText: '复制邮箱',
          success: (modalRes) => {
            if (modalRes.confirm) {
              wx.setClipboardData({
                data: 'contact@example.com',
                success: () => {
                  wx.showToast({ title: '已复制对接邮箱', icon: 'none' });
                }
              });
            }
          }
        });
      }
    });
  },

  onServiceTap(e) {
    const action = e.currentTarget.dataset.action;
    const title = e.currentTarget.dataset.title || '功能';
    this.runAction(action, title);
  },

  runAction(action, title) {
    switch (action) {
      case 'officialAfterSales':
        this.createRemoteOrder({
          serviceType: 'official_after_sales',
          deviceName: '官方售后咨询',
          issueDesc: '用户从工具箱发起官方售后咨询'
        });
        return;
      case 'openWorkOrders':
        wx.navigateTo({ url: '/pages/service-orders/service-orders' });
        return;
      case 'openServiceNotices':
        wx.switchTab({ url: '/pages/message/message' });
        return;
      case 'remoteGuideNow':
        this.openRemoteGuideFlow();
        return;
      case 'bookRemoteGuide':
        this.bookRemoteGuide();
        return;
      case 'buyParts':
        wx.showToast({ title: '该功能暂未开发', icon: 'none' });
        return;
      case 'uploadWarranty':
        wx.navigateTo({ url: '/pages/publish/publish' });
        return;
      case 'serviceRating':
        wx.navigateTo({ url: '/pages/comments/comments' });
        return;
      case 'goAiPrecheck':
        wx.navigateTo({ url: '/pages/ai/ai' });
        return;
      case 'faultCodeSearch':
        wx.navigateTo({ url: '/pages/search/search' });
        return;
      case 'maintenanceCalendar':
        this.openMaintenanceCalendar();
        return;
      case 'servicePriceReference':
        this.openServicePriceReference();
        return;
      default:
        wx.showToast({ title: title + '开发中', icon: 'none' });
    }
  },

  openRemoteGuideFlow() {
    wx.showActionSheet({
      itemList: ['空调/冰箱类', '洗衣机/厨房电器', '其他设备'],
      success: (res) => {
        const options = [
          {
            serviceType: 'remote_guide',
            deviceName: '空调/冰箱',
            issueDesc: '远程维修指导（空调/冰箱类）'
          },
          {
            serviceType: 'remote_guide',
            deviceName: '洗衣机/厨房电器',
            issueDesc: '远程维修指导（洗衣机/厨房电器）'
          },
          {
            serviceType: 'remote_guide',
            deviceName: '其他设备',
            issueDesc: '远程维修指导（其他设备）'
          }
        ];

        const selected = options[res.tapIndex] || options[0];
        this.createRemoteOrder(selected);
      }
    });
  },

  createRemoteOrder(preset) {
    const userInfo = wx.getStorageSync('userInfo') || {};
    app.onLoginReady((userId) => {
      wx.showLoading({ title: '提交中...' });
      createServiceOrder({
        userId,
        serviceType: preset.serviceType || 'remote_guide',
        deviceName: preset.deviceName || '待补充设备',
        issueDesc: preset.issueDesc || '待补充故障描述',
        contactPhone: userInfo.phone || '',
        preferredTime: ''
      }).then((result) => {
        wx.hideLoading();
        wx.showToast({
          title: result.source === 'remote' ? '预约成功' : '已本地保存',
          icon: 'success'
        });
        setTimeout(() => {
          wx.navigateTo({ url: '/pages/service-orders/service-orders' });
        }, 250);
      }).catch(() => {
        wx.hideLoading();
        wx.showToast({ title: '预约失败', icon: 'none' });
      });
    });
  },

  bookRemoteGuide() {
    wx.showModal({
      title: '立即预约远程指导',
      content: '将为你创建一条远程指导工单，你可在“我的工单”里查看处理进度。',
      confirmText: '确认预约',
      cancelText: '稍后',
      success: (res) => {
        if (res.confirm) {
          this.createRemoteOrder({
            serviceType: 'remote_guide',
            deviceName: '待补充设备',
            issueDesc: '远程维修指导预约（来自工具箱）'
          });
        }
      }
    });
  },

  openMaintenanceCalendar() {
    wx.showActionSheet({
      itemList: ['每月提醒', '每季度提醒', '每半年提醒'],
      success: (res) => {
        const map = ['每月', '每季度', '每半年'];
        const selected = map[res.tapIndex] || '每月';
        wx.setStorageSync('maintenance_reminder_cycle', selected);
        wx.showToast({ title: '已设置' + selected + '提醒', icon: 'none' });
      }
    });
  },

  openServicePriceReference() {
    wx.showModal({
      title: '维修价格参考',
      content: '上门检测通常 30~80 元，常见小修 80~300 元，具体以品牌和地区报价为准。',
      showCancel: false,
      confirmText: '知道了'
    });
  }
});
