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
    plugin: '浏览器插件已可以试用，欢迎体验！'
  },
  placeholder: '粘贴原文，或拖入 TXT / MD / 图片，也可点击右下角按钮上传...',
  upload: {
    label: '📎 上传',
    title: '上传图片 / TXT / MD'
  },
  pixiv: {
    placeholder: '粘贴 Pixiv 小说链接，自动导入原文…',
    action: '导入',
    loading: '导入中…',
    untitled: '（无标题）',
    imported: '已导入「{title}」',
    importedWithAuthor: '已导入「{title}」（作者：{author}）',
    failed: '导入失败，请检查链接是否正确',
    titleLabel: '标题',
    authorLabel: '作者',
    tagsLabel: '标签',
    descriptionLabel: '简介',
    includeMetadata: '翻译时附带标题/作者/标签/简介',
    translateMetadata: '翻译标题与简介',
    metadataLoading: '翻译中…',
    metadataFailed: '标题简介翻译失败',
    metadataResultTitle: '标题 / 简介译文',
    panelTitle: '从 Pixiv 导入',
    pasteHint: '粘贴截图、拖放或点击选择图片',
    maxImages: '一次最多 3 张图片',
    recognizing: '识别中…',
    recognizeFailed: '截图识别失败，换一张更清晰的截图试试',
    loginRequired: '请先登录后再使用截图识别',
    extractedInfo: '识别到的作品信息',
    searchKeywordLabel: '搜索关键词',
    searchAction: '重新搜索',
    searching: '搜索中…',
    searchFailed: '搜索失败，请稍后重试',
    searchNoResult: '没有找到匹配的作品，换个关键词试试',
    candidateImport: '导入此作品',
    r18Badge: 'R-18',
    charCount: '{count} 字',
    clear: '清除',
    shotClear: '取消识别'
  },
  targetLanguage: '目标语言',
  streaming: '流式输出',
  customPrompt: '自定义附加Prompt（可选）',
  start: '开始翻译',
  cancel: '取消翻译',
  retranslate: '重新翻译',
  retranslateHint: '强制重新翻译：忽略缓存和已有翻译结果，重新调用 AI',
  siteModelPrefix: '站方',
  status: {
    preparing: '正在连接服务器…',
    uploadingImages: '正在上传图片…',
    translating: 'AI 正在翻译…',
    translatingWithCount: 'AI 正在翻译…（已接收 {count} 字）'
  },
  errors: {
    tooManyImages: '一次最多上传 {max} 张图片',
    tooManyImagesKept: '一次最多上传 {max} 张图片，已保留前 {max} 张',
    imageModelFailed: '图片模型处理失败',
    translateFailed: '翻译失败'
  }
}
