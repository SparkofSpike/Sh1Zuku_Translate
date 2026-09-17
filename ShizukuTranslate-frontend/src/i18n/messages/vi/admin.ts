export default {
  heading: 'Bảng quản trị',
  subtitle: 'Tổng quan tiêu thụ khi gọi mô hình',
  refreshData: 'Làm mới dữ liệu',
  loading: 'Đang tải...',
  loadingUsage: 'Đang tải dữ liệu tiêu thụ...',
  loadingLogs: 'Đang tải nhật ký...',
  none: 'Chưa có',
  metrics: {
    totalTokens: 'Tổng Token',
    promptTokens: 'Token đầu vào',
    completionTokens: 'Token đầu ra',
    requestCount: 'Số lần gọi'
  },
  chart: {
    daily: 'Tiêu thụ 14 ngày gần đây',
    barTitle: '{date}: {tokens} Token',
    model: 'Tiêu thụ theo mô hình',
    noModelUsage: 'Chưa có bản ghi gọi mô hình'
  },
  users: {
    title: 'Tiêu thụ theo tài khoản',
    username: 'Tài khoản',
    totalTokens: 'Tổng Token',
    requestCount: 'Số lần gọi',
    latestUsedAt: 'Lần dùng gần nhất',
    detail: 'Xem chi tiết'
  },
  announce: {
    publish: 'Đăng thông báo',
    titlePlaceholder: 'Tiêu đề thông báo',
    contentModeLabel: 'Chế độ soạn nội dung thông báo',
    preview: 'Xem trước',
    contentPlaceholder: 'Nội dung thông báo',
    emptyPreview: 'Chưa có nội dung để xem trước',
    requireConfirmation: 'Cần người dùng xác nhận: thông báo này sẽ hiện ra khi người dùng truy cập website, sau khi bấm xác nhận sẽ không tự hiện lại nữa',
    publishing: 'Đang đăng...',
    publishedTitle: 'Thông báo đã đăng',
    needConfirm: 'Cần xác nhận',
    acknowledgements: 'Tình trạng xác nhận',
    noConfirmNeeded: 'Không cần xác nhận nữa',
    restoreConfirm: 'Khôi phục xác nhận',
    updating: 'Đang cập nhật...',
    empty: 'Chưa có thông báo'
  },
  detail: {
    title: 'Nhật ký Token của {username}',
    total: 'Tổng cộng',
    prompt: 'Đầu vào',
    completion: 'Đầu ra',
    time: 'Thời gian',
    provider: 'Giao thức',
    model: 'Mô hình',
    source: 'Nguồn',
    sum: 'Tổng',
    estimated: 'Ước tính',
    cacheBackfill: 'Thực tế (cache)',
    actual: 'Thực tế',
    empty: 'Chưa có nhật ký sử dụng token'
  },
  acks: {
    title: 'Người dùng đã xác nhận “{title}”',
    total: 'Tổng cộng {count} người đã xác nhận',
    username: 'Tên đăng nhập',
    email: 'Email',
    time: 'Thời gian xác nhận',
    empty: 'Chưa có người dùng xác nhận thông báo này'
  },
  presets: {
    title: 'Cài đặt dịch thuật',
    subtitle: 'Cài đặt được chèn vào prompt hệ thống theo tên; sau khi lưu có hiệu lực ngay, không cần triển khai lại',
    namePlaceholder: 'Tên cài đặt (ví dụ: Kaguya!)',
    promptPlaceholder: 'Nội dung cài đặt (yêu cầu dịch được chèn vào prompt hệ thống)',
    create: 'Tạo cài đặt',
    update: 'Lưu thay đổi',
    saving: 'Đang lưu...',
    empty: 'Chưa có cài đặt',
    confirmDelete: 'Bạn chắc chắn muốn xóa cài đặt này chứ? Người dùng sẽ không thể chọn lại nó.',
    errors: {
      load: 'Tải cài đặt thất bại',
      fillNamePrompt: 'Vui lòng điền tên và nội dung cài đặt',
      save: 'Lưu cài đặt thất bại',
      delete: 'Xóa cài đặt thất bại'
    },
    messages: {
      created: 'Đã tạo cài đặt',
      updated: 'Đã cập nhật cài đặt'
    }
  },
  logs: {
    title: 'Nhật ký tiện ích',
    subtitle: 'Báo cáo lỗi do tiện ích mở rộng trên trình duyệt gửi lên',
    submitter: 'Người gửi',
    version: 'Phiên bản',
    submittedAt: 'Thời gian gửi',
    errorMessage: 'Thông báo lỗi',
    empty: 'Chưa có nhật ký',
    prev: 'Trang trước',
    page: 'Trang {page}',
    next: 'Trang sau'
  },
  provider: {
    openai: 'Tương thích OpenAI'
  },
  errors: {
    loadUsage: 'Tải dữ liệu tiêu thụ token thất bại',
    loadUserLogs: 'Tải nhật ký token thất bại',
    loadAnnouncements: 'Tải thông báo thất bại: ',
    fillTitleContent: 'Vui lòng điền tiêu đề và nội dung thông báo',
    publishFailed: 'Đăng thất bại',
    loadAcks: 'Tải danh sách xác nhận thất bại',
    updateFailed: 'Cập nhật thông báo thất bại',
    deleteFailed: 'Xóa thất bại',
    loadLogs: 'Tải nhật ký thất bại'
  },
  messages: {
    published: 'Đăng thông báo thành công'
  },
  confirmDelete: 'Bạn chắc chắn muốn xóa thông báo này chứ?'
}
