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
