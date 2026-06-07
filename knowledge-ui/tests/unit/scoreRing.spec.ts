import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ScoreRing from '../../src/components/knowledge/ScoreRing.vue'
import SignalBars from '../../src/components/knowledge/SignalBars.vue'
import { i18n } from '../../src/i18n'

describe('ScoreRing + SignalBars', () => {
  it('ScoreRing renders two circles', () => {
    const w = mount(ScoreRing, { props: { value: 0.5 } })
    expect(w.findAll('circle').length).toBe(2)
  })
  it('SignalBars renders 6 bars from scores', () => {
    i18n.global.locale.value = 'de'
    const scores = { score_semantic:0.8, score_keyword:0.4, score_recency:0.3, score_importance:0.6, score_popularity:0.2, score_graph_proximity:0.5 }
    const w = mount(SignalBars, { props: { scores }, global: { plugins: [i18n] } })
    expect(w.findAll('.sig').length).toBe(6)
    expect(w.findAll('.fill').length).toBe(6)
  })
})
