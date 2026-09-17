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
  export: {
    label: '导出',
    exporting: '导出中…',
    failed: '导出失败，请重试',
    // First line of every exported file. {commit} = site build commit, {model} = model used.
    creditLine: '本文章由 Sh1Zuku_Translate {commit} 翻译，使用模型：{model}，仅供学习交流。'
      + '项目所有源码皆以 MIT 协议开源，代码地址：https://github.com/SparkofSpike/Sh1Zuku_Translate。'
      + '如有问题，请提 issue/PR；觉得还行，请您点个 star！'
      + '声明：AI 的生成结果可能并不准确，请仔细甄别。基于此译文产生的任何后果，Sh1Zuku_Translate网站及其维护者Sh1Zuku不承担任何法律责任。'
      + '公开发布时，可对以上水印删减，但请至少保留“AI翻译”“机翻”等标签。'
      + '同人万岁！热爱万岁！以下为正文——',
    format: {
      docx: 'Word 文档（.docx）',
      doc: 'Word 文档（.doc）',
      pdf: 'PDF 文档（.pdf）',
      txt: '纯文本（.txt）'
    }
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
