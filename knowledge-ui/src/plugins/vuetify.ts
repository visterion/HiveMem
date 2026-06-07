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
    defaultTheme: 'hivememDark',
    themes: {
      hivememDark: {
        dark: true,
        colors: {
          background: '#090B10', surface: '#12161F', 'surface-bright': '#181D29',
          primary: '#F4B740', secondary: '#46D6E0', accent: '#F4B740',
          warning: '#F4B740', error: '#F0676B', info: '#46D6E0', success: '#54C98C',
        },
      },
      hivememLight: {
        dark: false,
        colors: {
          background: '#EFEAE0', surface: '#FFFFFF', 'surface-bright': '#F3EEE3',
          primary: '#C98A1E', secondary: '#1F97A1', accent: '#C98A1E',
          warning: '#C98A1E', error: '#F0676B', info: '#1F97A1', success: '#54C98C',
        },
      },
    },
  },
  defaults: {
    VBtn: { rounded: 'lg' },
    VCard: { rounded: 'lg' },
  },
})
