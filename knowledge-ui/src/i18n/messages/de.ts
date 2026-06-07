// German message catalog. Keys are grouped by UI area. "HiveMem" is a brand
// name and is never translated; "Realms" is a HiveMem domain term kept as-is.
export default {
  common: {
    connecting: 'Verbindung…',
    loading: 'Lädt…',
    reload: 'Neu laden',
    noResults: 'Keine Ergebnisse.',
    comingSoon: 'In Arbeit',
  },
  nav: {
    search: 'Suche', hive: 'Hive', graph: 'Graph', realms: 'Realms', photos: 'Fotos',
    scans: 'Scans', timemachine: 'Time Machine', queen: 'Queen', settings: 'Einstellungen', cinema: 'Cinema',
  },
  tweaks: {
    appearance: 'Erscheinung', layout: 'Layout', theme: 'Modus', language: 'Sprache',
    accent: 'Akzent', density: 'Dichte', hive: 'Hive-Motiv',
  },
  settings: {
    signedInAs: 'Angemeldet als {name}',
    role: 'Rolle: {role}',
    mockMode: 'Mock-Modus',
    logout: 'Abmelden',
    language: 'Sprache'
  },
  search: {
    placeholder: 'Zum Suchen tippen…',
    searching: 'Suche läuft…',
    hudPlaceholder: 'Suchen…',
    comingSoon: 'Demnächst',
    stats: '{cells} Zellen · {facts} Fakten · {realms} Realms'
  },
  realms: {
    sizeMetric: 'GRÖSSENMETRIK',
    cellCount: 'Zellenanzahl',
    contentVolume: 'Inhaltsvolumen',
    importance: 'Wichtigkeitsgewichtet',
    popularity: 'Popularität',
    cells: '{n} Zellen'
  },
  reader: {
    markdown: 'Markdown',
    download: 'Datei herunterladen',
    editorTooltip: 'Editor — SP4',
    openReader: 'Reader öffnen',
    summary: 'ZUSAMMENFASSUNG',
    keyPoints: 'KERNPUNKTE',
    insight: 'ERKENNTNIS',
    text: 'TEXT',
    tunnels: 'TUNNEL ({n})',
    facts: 'FAKTEN ({n})',
    eml: {
      from: 'Von:',
      to: 'An:',
      subject: 'Betreff:',
      date: 'Datum:',
      parseError: 'E-Mail konnte nicht gelesen werden.'
    },
    imageAlt: 'Originaldokument'
  },
  queen: {
    activity: 'Queen-Aktivität',
    pending: 'Offene Vorschläge',
    accept: 'Annehmen',
    reject: 'Ablehnen',
    agent: 'Agent',
    cost: 'Kosten',
    duration: 'Dauer',
    started: 'Gestartet',
    status: 'Status',
    llmCalls: 'LLM-Aufrufe',
    trigger: 'Auslöser',
    noRuns: 'Noch keine Läufe.',
    noPending: 'Keine offenen Queen-Vorschläge.',
    unavailable: 'Vistierie nicht erreichbar — die Queen ist evtl. deaktiviert.',
    events: 'Ereignisse',
    run: 'Lauf {id}'
  },
  keybindings: {
    hints: 'Cmd+K Suche · Esc zurück · Enter Reader'
  }
}
