import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')
  const isAdmin = ref(localStorage.getItem('isAdmin') === 'true')
  const emailVerified = ref<boolean | null>(
    localStorage.getItem('emailVerified') === null ? null : localStorage.getItem('emailVerified') === 'true'
  )

  function setToken(newToken: string) {
    token.value = newToken
    localStorage.setItem('token', newToken)
  }

  function setAdmin(admin: boolean) {
    isAdmin.value = admin
    localStorage.setItem('isAdmin', admin ? 'true' : 'false')
  }

  function setEmailVerified(verified: boolean) {
    emailVerified.value = verified
    localStorage.setItem('emailVerified', verified ? 'true' : 'false')
  }

  function logout() {
    token.value = ''
    isAdmin.value = false
    emailVerified.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('isAdmin')
    localStorage.removeItem('emailVerified')
  }

  return { token, isAdmin, emailVerified, setToken, setAdmin, setEmailVerified, logout }
})
