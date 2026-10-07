const STORAGE_KEY = 'sms_ai_settings'
const SETTINGS_STORAGE_KEY = STORAGE_KEY

const DEFAULT_SETTINGS = {
  historyEnabled: true,
  showSources: true,
  motionEnabled: true
}

function normalizeSettings(value) {
  const raw = value && typeof value === 'object' ? value : {}
  return {
    historyEnabled: raw.historyEnabled !== false,
    showSources: raw.showSources !== false,
    motionEnabled: raw.motionEnabled !== false
  }
}

function loadSettings() {
  try {
    return normalizeSettings(wx.getStorageSync(STORAGE_KEY) || DEFAULT_SETTINGS)
  } catch (e) {
    return { ...DEFAULT_SETTINGS }
  }
}

function saveSettings(nextSettings) {
  const settings = normalizeSettings(nextSettings)
  wx.setStorageSync(STORAGE_KEY, settings)
  return settings
}

function updateSetting(key, value) {
  const settings = loadSettings()
  settings[key] = value
  return saveSettings(settings)
}

function updateSettings(patch) {
  const settings = loadSettings()
  const next = Object.assign({}, settings, patch || {})
  return saveSettings(next)
}

module.exports = {
  STORAGE_KEY,
  SETTINGS_STORAGE_KEY,
  DEFAULT_SETTINGS,
  loadSettings,
  saveSettings,
  updateSetting,
  updateSettings
}
