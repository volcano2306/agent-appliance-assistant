const app = getApp();

const LOCAL_UPLOADED_MANUALS_KEY = 'local_uploaded_manuals';

Page({
  data: {
    uploadFilePath: '',
    uploadFileName: '',
    coverImagePath: '',
    coverImageName: '',
    productName: '',
    brandName: '',
    modelName: '',
    categoryCode: '',
    shortNote: '',
    isPublic: false,
    submitting: false
  },

  onLoad() {},

  onChooseFileTap() {
    wx.chooseMessageFile({
      count: 1,
      type: 'file',
      extension: ['pdf'],
      success: (res) => {
        const file = (res.tempFiles && res.tempFiles[0]) || null;
        if (!file) return;

        const tempPath = file.path || '';
        const fileName = file.name || 'manual.pdf';
        if (!tempPath) return;

        wx.saveFile({
          tempFilePath: tempPath,
          success: (saveRes) => {
            this.setData({
              uploadFilePath: saveRes.savedFilePath || tempPath,
              uploadFileName: fileName
            });
          },
          fail: () => {
            this.setData({
              uploadFilePath: tempPath,
              uploadFileName: fileName
            });
          }
        });
      },
      fail: (err) => {
        if (err && err.errMsg && err.errMsg.includes('cancel')) return;
        wx.showToast({ title: '选择文件失败', icon: 'none' });
      }
    });
  },

  onChooseCoverTap() {
    wx.chooseImage({
      count: 1,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const tempPath = res.tempFilePaths && res.tempFilePaths[0];
        if (!tempPath) return;

        const name = tempPath.split('/').pop() || 'cover.jpg';
        wx.saveFile({
          tempFilePath: tempPath,
          success: (saveRes) => {
            this.setData({
              coverImagePath: saveRes.savedFilePath || tempPath,
              coverImageName: name
            });
          },
          fail: () => {
            this.setData({
              coverImagePath: tempPath,
              coverImageName: name
            });
          }
        });
      },
      fail: (err) => {
        if (err && err.errMsg && err.errMsg.includes('cancel')) return;
        wx.showToast({ title: '选择图片失败', icon: 'none' });
      }
    });
  },

  onFieldInput(e) {
    const field = e.currentTarget.dataset.field;
    if (!field) return;
    this.setData({ [field]: e.detail.value || '' });
  },

  onPublicSwitch(e) {
    this.setData({ isPublic: !!e.detail.value });
  },

  onSubmitUploadTap() {
    const {
      uploadFilePath,
      coverImagePath,
      productName,
      brandName,
      modelName,
      categoryCode,
      shortNote,
      isPublic,
      submitting
    } = this.data;

    if (submitting) return;
    if (!uploadFilePath) {
      wx.showToast({ title: '请先选择 PDF 文件', icon: 'none' });
      return;
    }
    if (!productName.trim()) {
      wx.showToast({ title: '请填写产品名称', icon: 'none' });
      return;
    }
    if (!app.globalData.loginReady || !app.globalData.userId) {
      wx.showToast({ title: '请先登录后再上传说明书', icon: 'none' });
      return;
    }

    this.setData({ submitting: true });
    const userId = app.globalData.userId;

    const uploadPdf = (imageUrl) => {
      wx.uploadFile({
        url: app.globalData.baseUrl + '/api/manuals/upload',
        filePath: uploadFilePath,
        name: 'file',
        formData: {
          userId,
          isPublic: isPublic ? 'true' : 'false',
          productName: productName.trim(),
          brandName: (brandName || '').trim(),
          modelName: (modelName || '').trim(),
          categoryCode: (categoryCode || '').trim(),
          summary: (shortNote || '').trim(),
          imageUrl: imageUrl || ''
        },
        success: (res) => {
          if (res.statusCode < 200 || res.statusCode >= 300) {
            wx.showToast({ title: '上传失败，请重试', icon: 'none' });
            return;
          }

          let payload = {};
          try {
            payload = JSON.parse(res.data || '{}');
          } catch (e) {}

          const remoteId = payload.myManualId || payload.manualId || '';
          if (!remoteId) {
            wx.showToast({ title: '上传失败，请重试', icon: 'none' });
            return;
          }

          this.saveLocalManual(userId, payload.imageUrl || imageUrl, remoteId, payload);
          wx.showToast({ title: '上传成功', icon: 'success' });
          this.resetUploadForm();
        },
        fail: () => {
          wx.showToast({ title: '上传失败，请检查后端', icon: 'none' });
        },
        complete: () => {
          this.setData({ submitting: false });
        }
      });
    };

    if (!coverImagePath) {
      uploadPdf('');
      return;
    }

    wx.uploadFile({
      url: app.globalData.baseUrl + '/api/manuals/upload-image',
      filePath: coverImagePath,
      name: 'image',
      formData: { userId },
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          let imageUrl = '';
          try {
            const payload = JSON.parse(res.data || '{}');
            imageUrl = payload.imageUrl || '';
          } catch (e) {}
          uploadPdf(imageUrl);
          return;
        }
        uploadPdf('');
      },
      fail: () => uploadPdf('')
    });
  },

  saveLocalManual(userId, imageUrl, syncedRemoteId, remotePayload = {}) {
    const list = wx.getStorageSync(LOCAL_UPLOADED_MANUALS_KEY) || [];
    list.unshift({
      id: 'local_' + Date.now(),
      syncedRemoteId: syncedRemoteId || '',
      myManualId: remotePayload.myManualId || '',
      manualId: remotePayload.manualId || '',
      ownerUserId: userId || '',
      name: this.data.productName.trim(),
      productName: this.data.productName.trim(),
      brandName: (this.data.brandName || '').trim(),
      modelName: (this.data.modelName || '').trim(),
      categoryCode: (this.data.categoryCode || '').trim(),
      summary: (this.data.shortNote || '').trim(),
      image: imageUrl || this.data.coverImagePath || '',
      filePath: this.data.uploadFilePath,
      fileName: this.data.uploadFileName,
      isPublic: !!this.data.isPublic,
      createdAt: new Date().toISOString()
    });
    wx.setStorageSync(LOCAL_UPLOADED_MANUALS_KEY, list.slice(0, 100));
  },

  resetUploadForm() {
    this.setData({
      uploadFilePath: '',
      uploadFileName: '',
      coverImagePath: '',
      coverImageName: '',
      productName: '',
      brandName: '',
      modelName: '',
      categoryCode: '',
      shortNote: '',
      isPublic: false
    });
  }
});
