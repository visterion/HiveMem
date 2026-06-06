import 'vuetify/styles'
import '@mdi/font/css/materialdesignicons.css'
import { createVuetify } from 'vuetify'
import { createVueI18nAdapter } from 'vuetify/locale/adapters/vue-i18n'
import { useI18n } from 'vue-i18n'
import { i18n } from '../i18n'

export const vuetify = createVuetify({
  locale: {
    adapter: createVueI18nAdapter({ i18n, useI18n })
  },
  theme: {
    defaultTheme: 'palace',
    themes: {
      palace: {
        dark: true,
        colors: {
          background: '#0a0a1a',
          surface: '#1a1a2e',
          'surface-bright': '#23233a',
          primary: '#00BFFF',
          secondary: '#00FF88',
          warning: '#FF8C00',
          accent: '#c8a84e',
          error: '#ff5577',
          info: '#00BFFF',
          success: '#00FF88',
        },
      },
    },
  },
  defaults: {
    VBtn: { rounded: 'lg' },
    VCard: { rounded: 'lg' },
  },
})
