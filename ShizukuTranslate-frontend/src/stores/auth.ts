import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')
  const isAdmin = ref(localStorage.getItem('isAdmin') === 'true')

  function setToken(newToken: string) {
    token.value = newToken
    localStorage.setItem('token', newToken)
  }

  function setAdmin(admin: boolean) {
    isAdmin.value = admin
    localStorage.setItem('isAdmin', admin ? 'true' : 'false')
  }

  function logout() {
    token.value = ''
    isAdmin.value = false
    localStorage.removeItem('token')
    localStorage.removeItem('isAdmin')
  }

  return { token, isAdmin, setToken, setAdmin, logout }
})
