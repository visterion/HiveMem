// English message catalog. Must have exactly the same key set as de.ts
// (enforced by the parity test in tests/unit/i18n.spec.ts).
export default {
  common: {
    connecting: 'Connecting…',
    loading: 'Loading…',
    reload: 'Reload',
    noResults: 'No results.'
  },
  nav: {
    search: 'Search',
    realms: 'Realms',
    settings: 'Settings'
  },
  settings: {
    signedInAs: 'Signed in as {name}',
    role: 'Role: {role}',
    mockMode: 'Mock mode',
    logout: 'Log out',
    language: 'Language'
  },
  search: {
    placeholder: 'Type to search…',
    searching: 'Searching…',
    hudPlaceholder: 'Search…',
    comingSoon: 'Coming soon',
    stats: '{cells} cells · {facts} facts · {realms} realms'
  },
  realms: {
    sizeMetric: 'SIZE METRIC',
    cellCount: 'Cell count',
    contentVolume: 'Content volume',
    importance: 'Importance-weighted',
    popularity: 'Popularity',
    cells: '{n} cells'
  },
  reader: {
    markdown: 'Markdown',
    download: 'Download file',
    editorTooltip: 'Editor — SP4',
    openReader: 'Open reader',
    summary: 'SUMMARY',
    keyPoints: 'KEY POINTS',
    insight: 'INSIGHT',
    text: 'TEXT',
    tunnels: 'TUNNELS ({n})',
    facts: 'FACTS ({n})',
    eml: {
      from: 'From:',
      to: 'To:',
      subject: 'Subject:',
      date: 'Date:',
      parseError: 'Failed to parse email.'
    },
    imageAlt: 'Original document'
  },
  queen: {
    activity: 'Queen activity',
    pending: 'Pending proposals',
    accept: 'Accept',
    reject: 'Reject',
    agent: 'Agent',
    cost: 'Cost',
    duration: 'Duration',
    started: 'Started',
    status: 'Status',
    llmCalls: 'LLM calls',
    trigger: 'Trigger',
    noRuns: 'No runs yet.',
    noPending: 'No pending Queen proposals.',
    unavailable: 'Vistierie unreachable — the Queen may be disabled.',
    events: 'Events',
    run: 'Run {id}'
  },
  keybindings: {
    hints: 'Cmd+K search · Esc back · Enter reader'
  }
}
