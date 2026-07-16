import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { vuetify } from './plugins/vuetify'
import { router } from './router'
import { i18n } from './i18n'
import { usePrefsStore } from './stores/prefs'
import { useUiStore } from './stores/ui'
import { registerSW } from 'virtual:pwa-register'
import App from './App.vue'
import './styles/tokens.css'
import './style.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(vuetify)
app.use(router)
app.use(i18n)

const prefs = usePrefsStore()
prefs.init()
vuetify.theme.global.name.value = prefs.theme === 'light' ? 'hivememLight' : 'hivememDark'

app.mount('#app')

const updateSW = registerSW({
  onNeedRefresh() { useUiStore().setSwUpdate(() => updateSW(true)) },
})
