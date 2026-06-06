import 'vuetify/styles'
import '@mdi/font/css/materialdesignicons.css'
import { createVuetify } from 'vuetify'
import { createVueI18nAdapter } from 'vuetify/locale/adapters/vue-i18n'
import { useI18n } from 'vue-i18n'
import { i18n } from '../i18n'

export const vuetify = createVuetify({
  locale: {
    // `i18n as any`: Vuetify's adapter is typed as I18n<any, …, string, …>, which
    // our typed createI18n instance (narrowed 'de'|'en' locale + message shapes) does
    // not satisfy. `vue-tsc -b` (the `npm run build` gate) fails on this without the
    // cast; runtime is unaffected. Known vue-i18n + Vuetify-adapter typing friction.
    adapter: createVueI18nAdapter({ i18n: i18n as any, useI18n })
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
