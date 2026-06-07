import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { vuetify } from './plugins/vuetify'
import { router } from './router'
import { i18n } from './i18n'
import App from './App.vue'
import './styles/tokens.css'
import './style.css'

createApp(App).use(createPinia()).use(vuetify).use(router).use(i18n).mount('#app')
