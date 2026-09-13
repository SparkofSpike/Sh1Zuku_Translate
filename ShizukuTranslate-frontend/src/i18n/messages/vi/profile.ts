export default {
  account: {
    username: 'Tên đăng nhập:',
    email: 'Email:',
    createdAt: 'Thời gian đăng ký:'
  },
  badge: {
    verified: '✓ Đã xác minh',
    unverified: 'Chưa xác minh'
  },
  emailVerify: {
    title: 'Xác minh Email',
    hint: 'Gửi mã xác minh tới địa chỉ email ở trên rồi điền vào là hoàn tất xác minh. Tài khoản chưa xác minh không thể dùng chức năng dịch (trên web và tiện ích mở rộng); nếu muốn đổi email, chỉ cần sửa địa chỉ email rồi xác minh lại.',
    emailPlaceholder: 'Địa chỉ email',
    resendIn: 'Gửi lại sau {seconds}s',
    sending: 'Đang gửi...',
    sendCode: 'Gửi mã xác minh',
    codePlaceholder: 'Mã xác minh',
    submitting: 'Đang xác minh...',
    submit: 'Xác minh và lưu',
    validityHint: 'Mã xác minh có hiệu lực trong 10 phút. Nếu email cũ không còn nhận được thư, hãy sửa trực tiếp thành email mới ở trên rồi gửi lại mã xác minh.',
    verified: 'Email đã được xác minh.',
    changeEmail: 'Đổi email',
    codeSent: 'Mã xác minh đã được gửi, vui lòng kiểm tra email',
    success: 'Xác minh email thành công, giờ bạn đã có thể dùng chức năng dịch'
  },
  usage: {
    label: 'Tổng Token đã dùng',
    meta: 'Đầu vào {input} · Đầu ra {output} · {count} lượt gọi'
  },
  model: {
    title: 'Cấu hình mô hình',
    hint: 'Bạn có thể lưu nhiều cấu hình và chọn riêng cho trang dịch trên web và tiện ích mở rộng trên trình duyệt.',
    add: 'Thêm cấu hình',
    site: 'Máy chủ',
    siteMeta: 'Dùng API Key DeepSeek do máy chủ cung cấp',
    selected: 'Đang chọn',
    apiKeyPrefix: 'API Key: ',
    usingSiteKey: 'Dùng Key của máy chủ',
    empty: 'Chưa có cấu hình mô hình cá nhân, hiện đang dùng máy chủ.',
    editTitle: 'Sửa cấu hình mô hình',
    createTitle: 'Thêm cấu hình mô hình',
    selectedSite: 'Đã chọn máy chủ',
    selectedPersonal: 'Đã chọn cấu hình mô hình cá nhân',
    noModels: 'Nhà cung cấp không trả về danh sách mô hình, vui lòng nhập thủ công',
    detected: 'Đã phát hiện {count} mô hình, hãy tích chọn những mô hình cần lưu',
    saved: 'Đã lưu cấu hình mô hình',
    keyCleared: 'Đã xóa Key, sẽ dùng máy chủ',
    deleted: 'Đã xóa cấu hình mô hình',
    confirmDelete: 'Bạn chắc chắn muốn xóa cấu hình mô hình này chứ?'
  },
  form: {
    name: 'Tên cấu hình',
    provider: 'Giao thức',
    modelName: 'Tên mô hình',
    modelPlaceholder: 'Nhập tên mô hình',
    addModel: 'Thêm',
    detecting: 'Đang kiểm tra...',
    detect: 'Tự động phát hiện',
    removeModel: 'Xóa {model}',
    detectedTitle: 'Mô hình phát hiện được (có thể chọn nhiều):',
    multiHint: 'Bạn có thể tích chọn nhiều mô hình cùng lúc; sau khi lưu, mỗi mô hình sẽ thành một cấu hình riêng.',
    baseUrl: 'Base URL',
    apiKey: 'API Key',
    apiKeyKeep: 'Để trống để giữ Key hiện tại',
    apiKeyOptional: 'Với DeepSeek có thể để trống để dùng Key của máy chủ',
    currentKeyPrefix: 'Key hiện tại: ',
    saving: 'Đang lưu...',
    save: 'Lưu cấu hình',
    clearKey: 'Xóa Key'
  },
  provider: {
    deepseek: 'DeepSeek',
    openai: 'Tương thích OpenAI',
    anthropic: 'Tương thích Anthropic'
  },
  plugin: {
    title: 'API Key cho tiện ích',
    hint: 'Dùng cho tiện ích mở rộng trên trình duyệt để gọi API dịch; sau khi tạo hãy lưu lại cẩn thận (chỉ hiển thị một lần).',
    generate: 'Tạo Key cho tiện ích'
  },
  errors: {
    loadProfile: 'Tải thông tin tài khoản thất bại',
    enterValidEmail: 'Vui lòng nhập địa chỉ email hợp lệ trước',
    codeSendFailed: 'Gửi mã xác minh thất bại',
    enterCode: 'Vui lòng nhập mã xác minh nhận được trong email',
    verifyFailed: 'Xác minh email thất bại',
    loadProfiles: 'Tải cấu hình mô hình thất bại',
    apiKeyRequired: 'Vui lòng nhập API Key trước khi kiểm tra mô hình',
    detectFailed: 'Kiểm tra mô hình thất bại, bạn có thể nhập tên mô hình thủ công',
    modelRequired: 'Vui lòng thêm ít nhất một tên mô hình',
    saveFailed: 'Lưu cấu hình mô hình thất bại',
    clearKeyFailed: 'Xóa Key thất bại',
    deleteFailed: 'Xóa cấu hình mô hình thất bại',
    loadUsage: 'Tải thông tin tiêu thụ thất bại',
    generateFailed: 'Tạo thất bại',
    copyFailed: 'Sao chép thất bại, vui lòng tự chọn và sao chép thủ công',
    loadKeys: 'Tải Key của tiện ích thất bại',
    deleteKeyFailed: 'Xóa thất bại'
  }
}
