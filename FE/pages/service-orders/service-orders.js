const app = getApp();
const { listServiceOrders, cancelServiceOrder } = require('../../utils/serviceOrders');

Page({
  data: {
    loading: false,
    orders: []
  },

  onLoad() {
    this.loadOrders();
  },

  onShow() {
    this.loadOrders();
  },

  loadOrders() {
    app.onLoginReady((userId) => {
      this.setData({ loading: true });
      listServiceOrders(userId)
        .then((orders) => {
          const next = (orders || []).map(item => Object.assign({}, item, {
            createdAtText: this.formatTime(item.createdAt)
          }));
          this.setData({ orders: next });
        })
        .catch(() => {
          this.setData({ orders: [] });
          wx.showToast({ title: '工单加载失败', icon: 'none' });
        })
        .finally(() => {
          this.setData({ loading: false });
        });
    });
  },

  formatTime(raw) {
    if (!raw) {
      return '';
    }
    const date = new Date(raw);
    if (Number.isNaN(date.getTime())) {
      return '';
    }
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    const hh = String(date.getHours()).padStart(2, '0');
    const mm = String(date.getMinutes()).padStart(2, '0');
    return `${y}-${m}-${d} ${hh}:${mm}`;
  },

  onCreateTap() {
    wx.navigateBack({
      fail: () => {
        wx.switchTab({ url: '/pages/toolbox/toolbox' });
      }
    });
  },

  onCancelTap(e) {
    const id = e.currentTarget.dataset.id;
    const order = (this.data.orders || []).find(item => item.id === id);
    if (!order || order.status === 'canceled' || order.status === 'done') {
      return;
    }

    wx.showModal({
      title: '取消工单',
      content: '确认取消该工单吗？',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        cancelServiceOrder(order)
          .then(() => {
            wx.showToast({ title: '已取消', icon: 'success' });
            this.loadOrders();
          })
          .catch(() => {
            wx.showToast({ title: '取消失败', icon: 'none' });
          });
      }
    });
  },

  onCardTap(e) {
    const id = e.currentTarget.dataset.id;
    const order = (this.data.orders || []).find(item => item.id === id);
    if (!order) {
      return;
    }

    wx.showModal({
      title: order.deviceName || '工单详情',
      content: `故障描述：${order.issueDesc || '无'}\n联系方式：${order.contactPhone || '未填写'}\n预约时间：${order.preferredTime || '待确认'}\n状态：${order.statusText || '待处理'}`,
      showCancel: false,
      confirmText: '我知道了'
    });
  }
});
