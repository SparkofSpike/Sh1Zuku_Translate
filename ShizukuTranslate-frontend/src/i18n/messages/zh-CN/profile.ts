export default {
  account: {
    username: '用户名：',
    email: '邮箱：',
    createdAt: '注册时间：'
  },
  badge: {
    verified: '✓ 已认证',
    unverified: '未认证'
  },
  emailVerify: {
    title: '邮箱认证',
    hint: '发送验证码到上方邮箱并填写后即可完成认证。未认证账号无法使用翻译功能（网页与插件）；如需更换邮箱，直接修改邮箱地址后重新认证即可。',
    emailPlaceholder: '邮箱地址',
    resendIn: '{seconds}s 后重发',
    sending: '发送中...',
    sendCode: '发送验证码',
    codePlaceholder: '验证码',
    submitting: '提交中...',
    submit: '验证并保存',
    validityHint: '验证码 10 分钟内有效。若原邮箱已无法收信，请直接在上方改为新邮箱后再发送验证码。',
    verified: '邮箱已验证。',
    changeEmail: '修改邮箱',
    codeSent: '验证码已发送，请查收邮件',
    success: '邮箱认证成功，现在可以使用翻译功能了'
  },
  usage: {
    label: '累计 Token 用量',
    meta: '输入 {input} · 输出 {output} · {count} 次调用'
  },
  model: {
    title: '模型配置',
    hint: '可以保存多条配置，在网页翻译和浏览器插件中分别选择。',
    add: '新增配置',
    site: '站方',
    siteMeta: '使用站方提供的 DeepSeek API Key',
    selected: '当前选择',
    apiKeyPrefix: 'API Key：',
    usingSiteKey: '使用站方 Key',
    empty: '还没有个人模型配置，当前使用站方。',
    editTitle: '编辑模型配置',
    createTitle: '新增模型配置',
    selectedSite: '已选择站方',
    selectedPersonal: '已选择个人模型配置',
    noModels: '供应商未返回模型列表，请手动填写',
    detected: '已检测到 {count} 个模型，请勾选需要保存的模型',
    saved: '模型配置已保存',
    keyCleared: 'Key 已清除，将使用站方',
    deleted: '模型配置已删除',
    confirmDelete: '确定删除这条模型配置吗？'
  },
  form: {
    name: '配置名称',
    provider: '协议',
    modelName: '模型名称',
    modelPlaceholder: '输入模型名称',
    addModel: '添加',
    detecting: '检测中...',
    detect: '自动检测',
    removeModel: '移除 {model}',
    detectedTitle: '检测到的模型（可多选）：',
    multiHint: '可以同时勾选多个模型；保存后每个模型会建立一条独立配置。',
    baseUrl: 'Base URL',
    apiKey: 'API Key',
    apiKeyKeep: '留空保持当前 Key',
    apiKeyOptional: 'DeepSeek 可留空使用站方 Key',
    currentKeyPrefix: '当前 Key：',
    saving: '保存中...',
    save: '保存配置',
    clearKey: '清除 Key'
  },
  provider: {
    deepseek: 'DeepSeek',
    openai: 'OpenAI 兼容',
    anthropic: 'Anthropic 兼容'
  },
  plugin: {
    title: '插件 API Key',
    hint: '用于浏览器插件调用翻译接口，生成后请妥善保存（只显示一次）。',
    generate: '生成插件 Key'
  },
  errors: {
    loadProfile: '加载个人资料失败',
    enterValidEmail: '请先输入正确的邮箱地址',
    codeSendFailed: '验证码发送失败',
    enterCode: '请填写邮箱中收到的验证码',
    verifyFailed: '邮箱认证失败',
    loadProfiles: '加载模型配置失败',
    apiKeyRequired: '请先填写 API Key 后再检测模型',
    detectFailed: '模型检测失败，可手动填写模型名称',
    modelRequired: '请至少添加一个模型名称',
    saveFailed: '保存模型配置失败',
    clearKeyFailed: '清除 Key 失败',
    deleteFailed: '删除模型配置失败',
    loadUsage: '加载用量失败',
    generateFailed: '生成失败',
    copyFailed: '复制失败，请手动选择复制',
    loadKeys: '加载插件 Key 失败',
    deleteKeyFailed: '删除失败'
  }
}
