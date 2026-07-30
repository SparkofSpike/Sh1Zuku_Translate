import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  { path: '/login', component: () => import('../views/LoginView.vue') },
  { path: '/register', component: () => import('../views/RegisterView.vue') },
  {
    path: '/about',
    component: () => import('../views/AboutView.vue')
  },
  {
    path: '/',
    component: () => import('../views/TranslateView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/history',
    component: () => import('../views/HistoryView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/history/:id',
    component: () => import('../views/HistoryDetailView.vue'),
    meta: { requiresAuth: true }
  },
  {
  path: '/survey',
  component: () => import('../views/SurveyView.vue'),
  meta: { requiresAuth: true }
},
{
  path: '/admin',
  component: () => import('../views/AdminView.vue'),
  meta: { requiresAuth: true }
}

  
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()
  if (to.meta.requiresAuth && !authStore.token) {
    next('/login')
  } else {
    next()
  }
})

export default router
