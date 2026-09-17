export default {
  presetSelector: {
    label: 'Preset bổ sung (có thể chọn nhiều)'
  },
  ocrPreview: {
    imageAlt: 'Ảnh đã tải lên {index}',
    removeImage: 'Xóa ảnh này',
    removeAll: 'Xóa tất cả',
    remove: 'Xóa',
    recognizing: 'Đang nhận dạng...',
    paddleOcr: 'PaddleOCR',
    paddleOcrWithCount: 'PaddleOCR ({count} ảnh)',
    repairSegments: 'Sửa phân đoạn',
    thresholdLabel: 'Độ tin cậy:'
  },
  // Shared by TranslateResult.vue and SseTranslateResult.vue: both render the same result block.
  translateResult: {
    heading: 'Kết quả dịch',
    // `|` must be escaped as {'|'} or vue-i18n parses it as the plural separator.
    tokenUsage: "Tiêu thụ Token: Đầu vào {prompt} {'|'} Đầu ra {completion} {'|'} Tổng {total}"
  },
  export: {
    label: 'Xuất file',
    exporting: 'Đang xuất…',
    failed: 'Xuất file thất bại, vui lòng thử lại',
    // First line of every exported file. {commit} = site build commit, {model} = model used.
    creditLine: 'Bài viết này được dịch bởi Sh1Zuku_Translate (build {commit}) sử dụng mô hình {model}, chỉ nhằm mục đích học tập và trao đổi. '
      + 'Toàn bộ mã nguồn được mở theo giấy phép MIT tại https://github.com/SparkofSpike/Sh1Zuku_Translate. '
      + 'Gặp vấn đề? Vui lòng tạo issue/PR; thấy ổn thì hãy cho chúng tôi một star! '
      + 'Tuyên bố: Kết quả do AI tạo ra có thể không chính xác — vui lòng cân nhắc kỹ. Sh1Zuku_Translate và người duy trì Sh1Zuku không chịu bất kỳ trách nhiệm pháp lý nào phát sinh từ bản dịch này. '
      + 'Khi công khai lại, bạn có thể lược bớt dòng này nhưng phải giữ các nhãn như "dịch bởi AI" hoặc "dịch máy". '
      + 'Tuổi đồng mãi mãi! Niềm đam mê mãi mãi! Nội dung chính ở dưới —',
    format: {
      docx: 'Tài liệu Word (.docx)',
      doc: 'Tài liệu Word (.doc)',
      pdf: 'Tài liệu PDF (.pdf)',
      txt: 'Văn bản thuần (.txt)'
    }
  },
  announcementPanel: {
    heading: 'Thông báo',
    collapse: 'Thu gọn',
    expand: 'Mở rộng',
    empty: 'Chưa có thông báo'
  },
  announcementConfirmDialog: {
    dialogLabel: 'Thông báo cần xác nhận',
    heading: 'Thông báo',
    intro: 'Có {count} thông báo cần bạn đọc và xác nhận, sau khi xác nhận sẽ không tự hiện lại nữa',
    confirming: 'Đang xác nhận...',
    acknowledge: 'Tôi đã đọc và xác nhận',
    confirmFailed: 'Xác nhận thất bại, vui lòng thử lại'
  }
}
