import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PhotoTile from '../../src/components/media/PhotoTile.vue'

const item = {
  cell_id: 'media-ph1', attachment_id: 'att-ph1', realm: 'private', summary: 'Foto 1',
  tags: [], mime_type: 'image/jpeg', size_bytes: 100, created_at: null, taken_at: null,
  width: 4032, height: 3024, camera_make: null, camera_model: null,
  gps_lat: null, gps_lon: null, place_name: null,
  thumbnail_uri: null, content_uri: null,
}

describe('PhotoTile', () => {
  it('renders an img pointing at the attachment thumbnail endpoint', () => {
    const w = mount(PhotoTile, { props: { item } })
    const img = w.find('img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('/api/attachments/att-ph1/thumbnail')
  })

  it('has a deterministic gradient background on the button', () => {
    const w = mount(PhotoTile, { props: { item } })
    expect(w.find('button.photo').attributes('style')).toContain('linear-gradient')
  })

  it('hides the img after a load error (gradient remains)', async () => {
    const w = mount(PhotoTile, { props: { item } })
    await w.find('img').trigger('error')
    expect(w.find('img').classes()).toContain('hidden')
  })

  it('emits open on click', async () => {
    const w = mount(PhotoTile, { props: { item } })
    await w.find('button.photo').trigger('click')
    expect(w.emitted('open')).toBeTruthy()
  })
})
