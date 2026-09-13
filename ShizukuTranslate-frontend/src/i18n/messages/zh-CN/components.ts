export default {
  presetSelector: {
    label: '附加预设（可多选）'
  },
  ocrPreview: {
    imageAlt: '上传的图片 {index}',
    removeImage: '移除这张',
    removeAll: '移除全部',
    remove: '移除',
    recognizing: '识别中...',
    paddleOcr: 'PaddleOCR',
    paddleOcrWithCount: 'PaddleOCR（{count} 张）',
    repairSegments: '修复分段',
    thresholdLabel: '置信度:'
  },
  // Shared by TranslateResult.vue and SseTranslateResult.vue: both render the same result block.
  translateResult: {
    heading: '翻译结果',
    // `|` must be escaped as {'|'} or vue-i18n parses it as the plural separator.
    tokenUsage: "Token 用量：输入 {prompt} {'|'} 输出 {completion} {'|'} 合计 {total}"
  },
  announcementPanel: {
    heading: '公告',
    collapse: '收起',
    expand: '展开',
    empty: '暂无公告'
  },
  announcementConfirmDialog: {
    dialogLabel: '待确认公告',
    heading: '公告',
    intro: '有 {count} 条公告需要您阅读并确认，确认后不再自动弹出',
    confirming: '确认中...',
    acknowledge: '我已阅读并确认',
    confirmFailed: '确认失败，请重试'
  }
}
