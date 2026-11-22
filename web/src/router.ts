// Shareable addresses. Hash history (#/s/...): the app is static files (Cloudflare Pages), so every address must load
// index.html. A link to a session the server has opens it anywhere (downloaded on the way, connection.ts).
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
    // the cloud server's admin page: by address only, not linked from the reviewer's settings. Two sections, each a
    // list or a map (?view=map): access keys and capture sessions; App.vue shows it for every route named admin*
    { path: '/admin', redirect: '/admin/keys' },
    { path: '/admin/keys', name: 'adminKeys', component: Empty },
    { path: '/admin/sessions', name: 'adminSessions', component: Empty },
    // settings, a page like the admin page (SettingsPage.vue): connection, language, what this browser keeps
    { path: '/settings', redirect: '/settings/connection' },
    { path: '/settings/connection', name: 'settingsConnection', component: Empty },
    { path: '/settings/language', name: 'settingsLanguage', component: Empty },
    { path: '/settings/browser', name: 'settingsBrowser', component: Empty },
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
  settings: () => ({ name: 'settingsConnection' }),
}
