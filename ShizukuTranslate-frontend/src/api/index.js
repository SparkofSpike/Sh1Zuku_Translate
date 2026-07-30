import axios from 'axios'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:5566/api/v1'
})

api.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * 上传图片进行 OCR 识别（支持竖排日文）
 */
export function ocrImage(file, polish = true, threshold = 0.5) {
  const formData = new FormData()
  formData.append('image', file)
  formData.append('polish', polish)
  formData.append('threshold', threshold)
  return api.post('/ocr', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 检查 OCR 服务状态
 */
export function checkOcrHealth() {
  return api.get('/ocr/health')
}

export default api
