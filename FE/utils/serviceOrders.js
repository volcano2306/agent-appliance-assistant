const { request } = require('./request');

const LOCAL_SERVICE_ORDERS_KEY = 'local_service_orders';

function getLocalOrders() {
  return wx.getStorageSync(LOCAL_SERVICE_ORDERS_KEY) || [];
}

function saveLocalOrders(list) {
  wx.setStorageSync(LOCAL_SERVICE_ORDERS_KEY, list || []);
}

function createLocalOrder(payload) {
  const now = new Date().toISOString();
  const order = {
    id: 'local_' + Date.now(),
    userId: payload.userId || '',
    source: 'local',
    serviceType: payload.serviceType || 'remote_guide',
    deviceName: payload.deviceName || '待补充设备',
    issueDesc: payload.issueDesc || '待补充故障描述',
    contactPhone: payload.contactPhone || '',
    preferredTime: payload.preferredTime || '',
    status: 'pending',
    statusText: '待处理',
    createdAt: now,
    updatedAt: now
  };

  const list = getLocalOrders();
  list.unshift(order);
  saveLocalOrders(list);
  return order;
}

function updateLocalOrder(id, patch) {
  const list = getLocalOrders();
  const next = list.map(item => item.id === id ? Object.assign({}, item, patch, { updatedAt: new Date().toISOString() }) : item);
  saveLocalOrders(next);
  return next.find(item => item.id === id) || null;
}

function listLocalOrders(userId) {
  const list = getLocalOrders();
  if (!userId) {
    return list;
  }
  return list.filter(item => item.userId === userId);
}

function normalizeRemoteOrder(order) {
  if (!order) {
    return null;
  }
  const statusTextMap = {
    pending: '待处理',
    processing: '处理中',
    done: '已完成',
    canceled: '已取消'
  };
  const status = order.status || 'pending';
  return Object.assign({}, order, {
    source: 'remote',
    statusText: order.statusText || statusTextMap[status] || '待处理'
  });
}

function createServiceOrder(payload) {
  return request({
    url: '/api/service-orders',
    method: 'POST',
    data: payload
  }).then(data => {
    const normalized = normalizeRemoteOrder(data);
    return {
      source: 'remote',
      order: normalized
    };
  }).catch(() => {
    const localOrder = createLocalOrder(payload);
    return {
      source: 'local',
      order: localOrder
    };
  });
}

function listServiceOrders(userId) {
  return request({
    url: '/api/service-orders?userId=' + encodeURIComponent(userId || '')
  }).then(data => {
    if (!Array.isArray(data)) {
      return [];
    }
    return data.map(normalizeRemoteOrder).filter(Boolean);
  }).catch(() => {
    return listLocalOrders(userId);
  });
}

function cancelServiceOrder(order) {
  if (!order || !order.id) {
    return Promise.reject(new Error('order id is required'));
  }

  if ((order.source || '').toLowerCase() === 'local' || String(order.id).startsWith('local_')) {
    const updated = updateLocalOrder(order.id, { status: 'canceled', statusText: '已取消' });
    return Promise.resolve(updated);
  }

  return request({
    url: '/api/service-orders/' + encodeURIComponent(order.id) + '/cancel',
    method: 'PUT',
    data: {
      userId: order.userId || ''
    }
  }).then(normalizeRemoteOrder);
}

module.exports = {
  createServiceOrder,
  listServiceOrders,
  cancelServiceOrder
};
