export default {
  heading: 'Dịch tiểu thuyết',
  emailGate: {
    title: 'Email chưa được xác minh',
    desc1: 'Để dịch vụ hoạt động ổn định và tránh bị lạm dụng, bạn cần xác minh email trước khi sử dụng chức năng dịch.',
    desc2: 'Việc xác minh chỉ mất một phút và không ảnh hưởng tới lịch sử hay cấu hình mô hình của bạn; tiện ích mở rộng trên trình duyệt cũng yêu cầu tài khoản đã xác minh email.',
    action: 'Tới trang 「Tài khoản」 để xác minh email'
  },
  openSource: {
    lead: 'Dự án đã được mở mã nguồn: ',
    tail: ' — star và follow của các bạn là động lực để mình tiếp tục cập nhật!',
    plugin: 'Tiện ích mở rộng cho trình duyệt hiện đã có thể dùng thử!'
  },
  placeholder: 'Dán văn bản gốc, hoặc kéo thả tệp TXT / MD / ảnh vào đây, cũng có thể bấm nút ở góc dưới bên phải để tải lên…',
  upload: {
    label: '📎 Tải lên',
    title: 'Tải lên ảnh / TXT / MD'
  },
  pixiv: {
    placeholder: 'Dán liên kết tiểu thuyết Pixiv để tự động nhập nội dung…',
    action: 'Nhập',
    loading: 'Đang nhập…',
    untitled: '(không có tiêu đề)',
    imported: 'Đã nhập “{title}”',
    importedWithAuthor: 'Đã nhập “{title}” (tác giả: {author})',
    failed: 'Nhập thất bại — hãy kiểm tra lại liên kết',
    titleLabel: 'Tiêu đề',
    authorLabel: 'Tác giả',
    tagsLabel: 'Thẻ',
    descriptionLabel: 'Tóm tắt',
    includeMetadata: 'Dịch kèm tiêu đề / tác giả / thẻ / tóm tắt',
    translateMetadata: 'Dịch tiêu đề & tóm tắt',
    metadataLoading: 'Đang dịch…',
    metadataFailed: 'Dịch tiêu đề/tóm tắt thất bại',
    metadataResultTitle: 'Bản dịch tiêu đề / tóm tắt',
    panelTitle: 'Nhập từ Pixiv',
    pasteHint: 'Dán ảnh chụp màn hình, kéo thả vào đây hoặc bấm để chọn ảnh',
    maxImages: 'Tối đa 3 ảnh mỗi lần',
    recognizing: 'Đang đọc ảnh chụp…',
    recognizeFailed: 'Không đọc được ảnh chụp — thử lại bằng ảnh rõ hơn',
    loginRequired: 'Hãy đăng nhập để dùng tính năng đọc ảnh chụp',
    extractedInfo: 'Thông tin đọc được từ ảnh chụp',
    searchKeywordLabel: 'Từ khoá tìm kiếm',
    searchAction: 'Tìm lại',
    modeTag: 'Theo thẻ',
    modeTitle: 'Theo tiêu đề',
    searchMatched: 'Đã tìm "{keyword}": {count} kết quả',
    searchTriedAll: 'Không tìm thấy kết quả cho "{keyword}"',
    autoImported: 'Đã tự động nhập tác phẩm gốc khớp: {title}',
    autoCandidates: 'Đã tìm thấy {count} ứng viên từ thông tin nhận dạng — xác nhận bằng “Nhập tác phẩm này”',
    searching: 'Đang tìm…',
    searchFailed: 'Tìm kiếm thất bại — vui lòng thử lại',
    searchNoResult: 'Không tìm thấy tác phẩm phù hợp — thử từ khoá khác nhé',
    candidateImport: 'Nhập tác phẩm này',
    r18Badge: 'R-18',
    charCount: '{count} ký tự',
    clear: 'Xoá hết',
    shotClear: 'Huỷ đọc ảnh'
  },
  targetLanguage: 'Ngôn ngữ đích',
  streaming: 'Xuất theo luồng',
  termFix: {
    label: 'Sửa thuật ngữ tự tạo cho tiểu thuyết dài',
    hint: 'Sẽ chậm hơn: trích xuất danh từ riêng trước khi dịch và kiểm tra nhất quán sau khi dịch.'
  },
  pipeline: {
    extractRunning: 'Đang trích xuất danh từ riêng…',
    extractDone: 'Đã trích xuất {count} danh từ riêng',
    translating: 'Đang dịch phần {current}/{total}',
    auditRunning: 'Đang kiểm tra nhất quán thuật ngữ…',
    auditDone: 'Kiểm tra xong — sửa {count} chỗ',
    auditClean: 'Kiểm tra xong — không phát hiện sai lệch',
    auditSkipped: 'Bỏ qua kiểm tra (không trích xuất được thuật ngữ)'
  },
  customPrompt: 'Prompt bổ sung tùy chỉnh (không bắt buộc)',
  start: 'Bắt đầu dịch',
  cancel: 'Hủy dịch',
  retranslate: 'Dịch lại',
  retranslateHint: 'Buộc dịch lại: bỏ qua bộ nhớ đệm và bản dịch có sẵn, gọi AI lần nữa',
  siteModelPrefix: 'Máy chủ',
  status: {
    preparing: 'Đang kết nối tới máy chủ…',
    uploadingImages: 'Đang tải ảnh lên…',
    translating: 'AI đang dịch…',
    translatingWithCount: 'AI đang dịch… (đã nhận {count} ký tự)'
  },
  errors: {
    tooManyImages: 'Mỗi lần chỉ được tải lên tối đa {max} ảnh',
    tooManyImagesKept: 'Mỗi lần chỉ được tải lên tối đa {max} ảnh, đã giữ lại {max} ảnh đầu tiên',
    imageModelFailed: 'Xử lý ảnh bằng mô hình thất bại',
    translateFailed: 'Dịch thất bại'
  }
}
