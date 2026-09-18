export default {
  heading: '小说翻译',
  emailGate: {
    title: '邮箱尚未认证',
    desc1: '为了保障服务稳定、防止账号被滥用，使用翻译功能前需要先完成邮箱认证。',
    desc2: '认证只需要一分钟，不会影响你的历史记录与模型配置；浏览器插件同样需要账号完成邮箱认证后才能使用。',
    action: '去「个人」页面认证邮箱'
  },
  openSource: {
    lead: '项目已开源：',
    tail: '，你们的 star 和 follow 是我更新的动力！',
    plugin: '浏览器插件正在锐意研发中，预计九月初正式可用……'
  },
  placeholder: '粘贴原文，或拖入 TXT / MD / 图片，也可点击右下角按钮上传...',
  upload: {
    label: '📎 上传',
    title: '上传图片 / TXT / MD'
  },
  targetLanguage: '目标语言',
  streaming: '流式输出',
  imageMode: {
    label: '图片处理：',
    model: '模型处理',
    ocr: 'OCR处理'
  },
  customPrompt: '自定义附加Prompt（可选）',
  start: '开始翻译',
  cancel: '取消翻译',
  retranslate: '重新翻译',
  retranslateHint: '强制重新翻译：忽略缓存和已有翻译结果，重新调用 AI',
  siteModelPrefix: '站方',
  status: {
    preparing: '正在连接服务器…',
    translating: 'AI 正在翻译…',
    translatingWithCount: 'AI 正在翻译…（已接收 {count} 字）'
  },
  errors: {
    tooManyImages: '一次最多上传 {max} 张图片',
    tooManyImagesKept: '一次最多上传 {max} 张图片，已保留前 {max} 张',
    noText: '未识别到文字',
    ocrFailed: 'OCR 请求失败',
    imageModelFailed: '图片模型处理失败',
    translateFailed: '翻译失败'
  }
}
