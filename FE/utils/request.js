const app = getApp()

function resolveCurrentUserId() {
  if (app && typeof app.resolveUserId === 'function') {
    return app.resolveUserId() || ''
  }
  return (app.globalData && app.globalData.userId) || ''
}

function resolveErrorCode(data) {
  if (!data || typeof data !== 'object') return ''
  const code = data.error || data.code || ''
  return (code || '').toString().trim().toLowerCase()
}

function shouldForceLogout(res) {
  const status = Number(res && res.statusCode)
  if (status === 401 || status === 403) return true
  const errorCode = resolveErrorCode(res && res.data)
  return errorCode === 'login_required' || errorCode === 'invalid_user'
}

function request(options) {
  return doRequest(options || {})
}

function doRequest(options) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: buildUrl(options.url || ''),
      method: options.method || 'GET',
      data: options.data || {},
      header: {
        'Content-Type': 'application/json',
        ...(options.header || {}),
        'X-User-Id': resolveCurrentUserId()
      },
      success: res => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.data)
          return
        }

        if (shouldForceLogout(res) && app && typeof app.logout === 'function') {
          app.logout()
        }

        reject(res)
      },
      fail: err => reject(err)
    })
  })
}

function buildUrl(url) {
  if (/^https?:\/\//i.test(url)) {
    return url
  }
  const baseUrl = ((app.globalData && app.globalData.baseUrl) || '').replace(/\/$/, '')
  const path = url.startsWith('/') ? url : `/${url}`
  return baseUrl + path
}

module.exports = { request }
