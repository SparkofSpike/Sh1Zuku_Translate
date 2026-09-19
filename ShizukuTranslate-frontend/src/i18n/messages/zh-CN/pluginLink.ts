export default {
  title: '插件授权',
  permission: '插件将获得访问你账号的权限，可用网站已配置的模型与预设进行翻译。',
  code: '授权码',
  allow: '允许',
  cancel: '取消',
  submitting: '授权中…',
  success: '授权成功，请回到插件窗口完成设置，本页可以关闭。',
  successKeyName: '本次授权的密钥名称：{name}',
  cancelled: '已取消授权。本页可以关闭，插件端会一直等待至超时。',
  errors: {
    incompleteTitle: '链接不完整',
    incomplete: '链接里缺少授权码，请回到插件窗口重新发起授权。',
    invalidOrExpired: '授权码无效或已过期，请回到插件窗口重新获取授权码。',
    emailNotVerified: '你的账号邮箱尚未验证，请先完成邮箱验证，再回到本页重新授权。',
    emailNotVerifiedAction: '去验证邮箱',
    alreadyApproved: '该授权码已被使用，请回到插件窗口重新获取新的授权码。',
    unknown: '授权失败，请稍后重试。'
  }
}
