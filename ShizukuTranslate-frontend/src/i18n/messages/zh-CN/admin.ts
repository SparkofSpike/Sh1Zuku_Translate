export default {
  heading: '管理员面板',
  subtitle: '模型调用用量概览',
  refreshData: '刷新数据',
  loading: '加载中...',
  loadingUsage: '加载用量中...',
  loadingLogs: '加载日志中...',
  none: '暂无',
  metrics: {
    totalTokens: '总 Token',
    promptTokens: '输入 Token',
    completionTokens: '输出 Token',
    requestCount: '调用次数'
  },
  chart: {
    daily: '近 14 日用量',
    barTitle: '{date}: {tokens} Token',
    model: '模型用量',
    noModelUsage: '暂无模型调用记录'
  },
  users: {
    title: '账户用量',
    username: '账户',
    totalTokens: '总 Token',
    requestCount: '调用次数',
    latestUsedAt: '最新使用',
    detail: '查看详情'
  },
  announce: {
    publish: '发布公告',
    titlePlaceholder: '公告标题',
    contentModeLabel: '公告内容编辑模式',
    preview: '预览',
    contentPlaceholder: '公告内容',
    emptyPreview: '暂无内容可预览',
    requireConfirmation: '需要用户确认：用户访问网站时将弹出此公告，点击确认后不再自动弹出',
    publishing: '发布中...',
    publishedTitle: '已发布公告',
    needConfirm: '需确认',
    acknowledgements: '确认情况',
    noConfirmNeeded: '不再需要确认',
    restoreConfirm: '恢复需确认',
    updating: '更新中...',
    empty: '暂无公告'
  },
  detail: {
    title: '{username} 的 Token 日志',
    total: '总计',
    prompt: '输入',
    completion: '输出',
    time: '时间',
    provider: '协议',
    model: '模型',
    source: '来源',
    sum: '合计',
    estimated: '估算',
    cacheBackfill: '缓存实际',
    actual: '实际',
    empty: '暂无 token 使用日志'
  },
  acks: {
    title: '「{title}」已确认用户',
    total: '共 {count} 人已确认',
    username: '用户名',
    email: '邮箱',
    time: '确认时间',
    empty: '暂无用户确认此公告'
  },
  presets: {
    title: '翻译预设',
    subtitle: '预设按名称注入系统提示词，保存后立即生效，无需重新部署',
    namePlaceholder: '预设名称（如：超时空辉夜姬！）',
    promptPlaceholder: '预设内容（追加到系统提示词的翻译要求）',
    create: '新建预设',
    update: '保存修改',
    saving: '保存中...',
    empty: '暂无预设',
    confirmDelete: '确定要删除这个预设吗？正在使用它的用户将无法再选择它。',
    errors: {
      load: '加载预设失败',
      fillNamePrompt: '请填写预设名称和内容',
      save: '保存预设失败',
      delete: '删除预设失败'
    },
    messages: {
      created: '预设创建成功',
      updated: '预设更新成功'
    }
  },
  logs: {
    title: '插件日志',
    subtitle: '浏览器插件提交的错误报告',
    submitter: '提交者',
    version: '版本',
    submittedAt: '提交时间',
    errorMessage: '错误信息',
    empty: '暂无日志',
    prev: '上一页',
    page: '第 {page} 页',
    next: '下一页'
  },
  provider: {
    openai: 'OpenAI 兼容'
  },
  errors: {
    loadUsage: '加载 token 用量失败',
    loadUserLogs: '加载 token 日志失败',
    loadAnnouncements: '加载公告失败：',
    fillTitleContent: '请填写公告标题和内容',
    publishFailed: '发布失败',
    loadAcks: '加载确认列表失败',
    updateFailed: '更新公告失败',
    deleteFailed: '删除失败',
    loadLogs: '加载日志失败'
  },
  messages: {
    published: '公告发布成功'
  },
  confirmDelete: '确定要删除这条公告吗？'
}
