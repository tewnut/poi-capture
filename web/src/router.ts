// Shareable addresses. Hash history (#/s/...): the app is static files (Cloudflare Pages), so every address must load
// index.html. A link opens the session, place or task only in a browser where that session was imported.
import { createRouter, createWebHashHistory } from 'vue-router'

const Empty = { render: () => null }   // the views are drawn by App.vue from the route; no routed component

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'sessions', component: Empty },
    { path: '/tasks', name: 'tasks', component: Empty },
    { path: '/s/:ds', name: 'session', component: Empty },
    { path: '/s/:ds/tasks', name: 'sessionTasks', component: Empty },
    { path: '/s/:ds/p/:place', name: 'place', component: Empty },
    { path: '/s/:ds/t/:task', name: 'task', component: Empty },
    { path: '/:rest(.*)*', redirect: '/' },
  ],
})

export const to = {
  sessions: () => ({ name: 'sessions' }),
  tasks: () => ({ name: 'tasks' }),
  session: (ds: string) => ({ name: 'session', params: { ds } }),
  sessionTasks: (ds: string) => ({ name: 'sessionTasks', params: { ds } }),
  place: (ds: string, place: string) => ({ name: 'place', params: { ds, place } }),
  task: (ds: string, task: number) => ({ name: 'task', params: { ds, task: String(task) } }),
}
