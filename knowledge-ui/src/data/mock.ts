// Demo dataset for the Mock API client.
// Content below paraphrases publicly-known computer-science facts (papers, RFCs,
// reference docs). URLs link to primary sources. Swap in HttpApiClient for real data.

import type { Cell, Realm, Tunnel, Fact, Reference } from '../api/types'

const t = (iso: string) => iso

const cells: Cell[] = [
  // ── Distributed Systems ─────────────────────────────────────────
  {
    id: 'ds-raft',
    realm: 'Distributed Systems',
    signal: 'Consensus',
    topic: 'Raft',
    title: 'Raft consensus algorithm',
    content:
      '# Raft\n\nRaft is a consensus algorithm designed to be understandable. A single elected **leader** receives all client writes, replicates them to followers as log entries, and commits an entry once a majority has persisted it. If the leader fails, a randomized election timeout (typically 150–300 ms) triggers a new election.\n\nDecomposes consensus into three sub-problems: **leader election**, **log replication**, and **safety**.',
    summary:
      'Leader-based consensus decomposed into election, log replication, and safety — deliberately designed to be easier to understand than Paxos.',
    key_points: [
      'Majority quorum for commits (f+1 of 2f+1 nodes tolerate f failures)',
      'Randomized election timeout avoids split votes',
      'Leader append-only log; followers truncate conflicts',
      'Used by etcd, Consul, CockroachDB, TiKV',
    ],
    insight:
      'Understandability itself was a design goal — Ongaro\'s user study showed Raft beat Paxos on student comprehension, which matters for real-world implementation correctness.',
    tags: ['consensus', 'distributed', 'paper'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2014-10-01T00:00:00Z'),
    valid_from: t('2014-10-01T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'ds-paxos',
    realm: 'Distributed Systems',
    signal: 'Consensus',
    topic: 'Paxos',
    title: 'Paxos: The Part-Time Parliament',
    content:
      'Leslie Lamport\'s 1998 paper describes consensus via an allegory of a Greek island parliament. Roles: **proposer**, **acceptor**, **learner**. Two phases — *prepare/promise* and *accept/accepted* — guarantee safety even under asynchrony and crash failures.\n\nNotoriously hard to follow; Multi-Paxos extends it to a log, and most production systems use derivatives (Raft, Zab, Viewstamped Replication).',
    summary:
      'Two-phase consensus protocol by Lamport — safe under asynchrony, famously opaque; basis for most production consensus.',
    key_points: [
      'Prepare/Promise + Accept/Accepted phases',
      'Safety always; liveness requires a stable leader',
      'Multi-Paxos amortizes phase 1 across many values',
      'Zab (ZooKeeper) and VR are close cousins',
    ],
    insight:
      'FLP (1985) proved deterministic async consensus impossible with one crash. Paxos sidesteps by sacrificing liveness, not safety — the right trade for most systems.',
    tags: ['consensus', 'distributed', 'paper', 'lamport'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('1998-05-01T00:00:00Z'),
    valid_from: t('1998-05-01T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'ds-mapreduce',
    realm: 'Distributed Systems',
    signal: 'Architecture',
    topic: 'Batch Processing',
    title: 'MapReduce',
    content:
      'Dean & Ghemawat (Google, OSDI 2004) described MapReduce: users supply a `map(k1,v1) → list(k2,v2)` and `reduce(k2, list(v2)) → list(v2)`. The framework handles partitioning, scheduling, failures, and re-execution.\n\nRan on GFS (Google File System) commodity clusters. Hadoop\'s open-source port drove a decade of big-data infrastructure.',
    summary:
      'Functional map + reduce abstraction over a cluster; framework handles faults and data locality.',
    key_points: [
      'Sort-based shuffle between map and reduce phases',
      'Deterministic reduce enables re-execution on worker crash',
      'Superseded internally by Dataflow/Beam; still influential',
    ],
    insight:
      'The real insight wasn\'t the API — it was constraining users to deterministic pure functions so the framework could handle faults transparently.',
    tags: ['batch', 'google', 'paper'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2004-12-06T00:00:00Z'),
    valid_from: t('2004-12-06T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'ds-dynamo',
    realm: 'Distributed Systems',
    signal: 'Architecture',
    topic: 'Key-Value Stores',
    title: 'Amazon Dynamo',
    content:
      'DeCandia et al. (Amazon, SOSP 2007) built an always-writable key-value store for the shopping cart. Uses **consistent hashing** for partitioning, **vector clocks** for version reconciliation, and **sloppy quorum + hinted handoff** for availability.\n\nInfluenced Cassandra, Riak, Voldemort. DynamoDB is a managed successor but architecturally distinct.',
    summary:
      'Highly-available, eventually-consistent key-value store — consistent hashing, vector clocks, sloppy quorum.',
    key_points: [
      'N/R/W tunable (e.g. N=3, R=2, W=2 for strong read-your-writes on single region)',
      'Read repair + Merkle trees for anti-entropy',
      'Application resolves concurrent versions (e.g. cart union)',
    ],
    insight:
      'Dynamo picked AP on CAP because "add-to-cart must never fail" — the business cost of a rejected write exceeded the cost of merging duplicate carts later.',
    tags: ['kv-store', 'amazon', 'paper', 'eventual-consistency'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2007-10-14T00:00:00Z'),
    valid_from: t('2007-10-14T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'ds-cap',
    realm: 'Distributed Systems',
    signal: 'Theory',
    topic: 'Trade-offs',
    title: 'CAP theorem',
    content:
      'Eric Brewer\'s 2000 PODC keynote conjecture, formalized by Gilbert & Lynch (2002): any networked shared-data system can provide at most **two** of Consistency, Availability, and Partition-tolerance.\n\nSince network partitions are unavoidable in practice, real systems choose CP (refuse writes during a partition) or AP (accept divergent writes).',
    summary:
      'In a partition you must choose: linearizable consistency OR availability. You cannot have both.',
    key_points: [
      'C = linearizability; A = every request gets a non-error response',
      'PACELC refines: Else (no partition), trade Latency vs Consistency',
      'Spanner claims CP+low latency via TrueTime atomic clocks',
    ],
    insight:
      'CAP is often misquoted as "pick two of three". Partitions happen whether you want them or not — the real choice is CP vs AP during a partition.',
    tags: ['theory', 'distributed'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2002-06-01T00:00:00Z'),
    valid_from: t('2002-06-01T00:00:00Z'),
    valid_until: null,
  },

  // ── Databases ───────────────────────────────────────────────────
  {
    id: 'db-mvcc',
    realm: 'Databases',
    signal: 'Concurrency',
    topic: 'PostgreSQL',
    title: 'MVCC in PostgreSQL',
    content:
      'PostgreSQL uses Multi-Version Concurrency Control: every row version carries `xmin` (creating txid) and `xmax` (deleting txid). Readers see the snapshot visible to their transaction without taking locks; writers create new versions instead of overwriting.\n\n`VACUUM` reclaims tuples whose `xmax` is below every active snapshot\'s `xmin`.',
    summary:
      'Readers never block writers and vice versa — each transaction sees a snapshot via per-tuple xmin/xmax.',
    key_points: [
      'xmin/xmax are 32-bit txids (wraparound is a real operational concern)',
      'Repeatable-read snapshot taken at first statement; serializable uses SSI',
      'VACUUM FULL rewrites the table; plain VACUUM only marks space reusable',
    ],
    insight:
      'The "bloat" tradeoff — MVCC buys lock-free reads at the cost of background vacuum pressure. Heavy-update tables need autovacuum tuning, not just indexes.',
    tags: ['postgres', 'concurrency'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2023-01-01T00:00:00Z'),
    valid_from: t('2023-01-01T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'db-wal',
    realm: 'Databases',
    signal: 'Durability',
    topic: 'Write-Ahead Log',
    title: 'Write-ahead logging (WAL)',
    content:
      'Before a dirty page is flushed to the heap, its change record must be durably written to the log (the D in ARIES). On crash, redo replays WAL forward from the last checkpoint; undo rolls back uncommitted transactions.\n\nPostgres WAL is also the basis for streaming replication and point-in-time recovery.',
    summary:
      'Log first, then modify pages. Crash recovery replays the log; replication streams it.',
    key_points: [
      'Commit = log record `fsync`\'d, not data pages',
      'Checkpoints bound recovery time by flushing dirty pages',
      'pg_wal segments are 16 MB by default',
    ],
    insight:
      'fsync durability assumptions break on consumer SSDs that lie about flush — why enterprise DBs are paranoid about disk testing (see the 2018 Postgres fsync-gate debate).',
    tags: ['durability', 'recovery', 'postgres'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('1992-01-01T00:00:00Z'),
    valid_from: t('1992-01-01T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'db-btree-lsm',
    realm: 'Databases',
    signal: 'Storage',
    topic: 'Index Structures',
    title: 'B-tree vs LSM-tree',
    content:
      'B-trees (Postgres, InnoDB) update in place — reads are cheap, random writes amplify. LSM-trees (RocksDB, Cassandra, LevelDB) buffer writes in memory, flush to immutable sorted runs, then compact — writes are cheap, reads pay compaction tax.\n\nChoose B-tree for read-heavy OLTP; LSM for write-heavy ingest.',
    summary:
      'B-tree: fast reads, write amplification. LSM: fast writes, read amplification from compaction.',
    key_points: [
      'Write amp (LSM): level size ratio × levels',
      'Read amp (LSM): bloom filters reduce but don\'t eliminate it',
      'Space amp differs: LSM tombstones until compacted',
    ],
    insight:
      'FoundationDB and modern cloud databases often layer: LSM for ingest, periodic compaction to columnar for analytics — neither structure is universally best.',
    tags: ['storage', 'indexing'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2024-01-01T00:00:00Z'),
    valid_from: t('2024-01-01T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'db-pgvector',
    realm: 'Databases',
    signal: 'Storage',
    topic: 'Vector Search',
    title: 'pgvector: vector similarity in Postgres',
    content:
      'pgvector adds a `vector` column type and index methods **IVFFlat** (inverted file with flat quantizer) and **HNSW** (hierarchical navigable small world) for approximate nearest neighbor.\n\nDistance operators: `<->` Euclidean distance, `<#>` negative inner product, `<=>` cosine.',
    summary:
      'Postgres extension for ANN search; IVFFlat and HNSW indexes; Euclidean, inner-product, and cosine operators.',
    key_points: [
      'HNSW build is memory-hungry but queries faster than IVFFlat',
      'Default 2000-dim upper limit per column',
      'ivfflat `lists` tunable; rule of thumb rows/1000',
    ],
    insight:
      'Colocating vectors with relational data means no separate vector DB to sync — the 90% case where retrieval and metadata filters must share a transaction.',
    tags: ['postgres', 'vector-search', 'ann'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2021-04-20T00:00:00Z'),
    valid_from: t('2021-04-20T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'db-redis',
    realm: 'Databases',
    signal: 'Storage',
    topic: 'In-Memory',
    title: 'Redis: data structures server',
    content:
      'Salvatore Sanfilippo\'s in-memory store (2009), single-threaded command loop, O(1) or O(log n) operations over strings, lists, hashes, sets, sorted sets, streams, HyperLogLog.\n\nPersistence: **RDB** periodic snapshot + **AOF** append-only command log. Default TCP port **6379**.',
    summary:
      'Single-threaded in-memory KV with rich data structures; RDB+AOF persistence.',
    key_points: [
      'Single-threaded avoids locking; IO multiplexed with epoll/kqueue',
      'Cluster mode: 16384 hash slots across shards',
      'Forked from BSD-3 to SSPL/RSAL (2024); Valkey is the Linux Foundation fork',
    ],
    insight:
      'The "data structures server" framing — not just a cache — is why Redis crept into leaderboards, rate limiters, session stores, and job queues far beyond key-value.',
    tags: ['redis', 'in-memory', 'kv-store'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2009-05-10T00:00:00Z'),
    valid_from: t('2009-05-10T00:00:00Z'),
    valid_until: null,
  },

  // ── Networking ──────────────────────────────────────────────────
  {
    id: 'net-tcp',
    realm: 'Networking',
    signal: 'Transport',
    topic: 'TCP',
    title: 'TCP three-way handshake',
    content:
      'Connection setup: client sends **SYN** with initial sequence number (ISN), server replies **SYN-ACK** with its own ISN, client responds **ACK**. After this, both sides know each other\'s starting sequence and receive window.\n\nCongestion control (Reno, CUBIC, BBR) governs how fast the sender ramps up.',
    summary:
      'SYN → SYN-ACK → ACK. Establishes sequence numbers and receive windows.',
    key_points: [
      'ISN randomized since RFC 1948 to thwart prediction attacks',
      'TIME_WAIT holds closed sockets 2×MSL (typically 60s)',
      'CUBIC default on Linux since 2.6.19; BBR (Google, 2016) gaining ground',
    ],
    insight:
      'Every RTT spent in handshake is perceptual latency. QUIC\'s 0-RTT resumption is a direct attack on this cost, not just a protocol curio.',
    tags: ['tcp', 'rfc'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('1981-09-01T00:00:00Z'),
    valid_from: t('1981-09-01T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'net-quic',
    realm: 'Networking',
    signal: 'Transport',
    topic: 'QUIC',
    title: 'QUIC (RFC 9000)',
    content:
      'UDP-based transport with TLS 1.3 built in. Connection setup and crypto handshake combine into one round trip (0-RTT for resumption). Multiplexed streams avoid head-of-line blocking — a lost packet only stalls its own stream, not the whole connection.\n\nConnection ID is decoupled from (IP, port), so connections survive network migration (e.g. Wi-Fi → LTE).',
    summary:
      'UDP-based, TLS-integrated transport with per-stream HoL isolation and connection migration.',
    key_points: [
      'Finalized as RFC 9000 in May 2021',
      'Originally Google\'s gQUIC; standardized by IETF',
      'HTTP/3 is QUIC + HTTP semantics (RFC 9114)',
    ],
    insight:
      'Moving transport to user space (UDP + library) lets features ship faster than kernel TCP — the real architectural shift, not the handshake count.',
    tags: ['quic', 'rfc', 'transport'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2021-05-27T00:00:00Z'),
    valid_from: t('2021-05-27T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'net-http2',
    realm: 'Networking',
    signal: 'Application',
    topic: 'HTTP',
    title: 'HTTP/2 multiplexing',
    content:
      'RFC 7540 (2015) replaces HTTP/1.1\'s text-line framing with a binary frame layer. Many concurrent **streams** share one TCP connection; **HPACK** compresses redundant headers. Server push exists but is deprecated (rarely beneficial in practice).\n\nStill suffers TCP head-of-line blocking — one lost packet stalls all streams.',
    summary:
      'Binary framing, header compression, stream multiplexing over a single TCP connection.',
    key_points: [
      'HPACK uses static + dynamic tables to shrink header size',
      'Flow control per stream and per connection',
      'TCP HoL blocking is the reason QUIC/HTTP-3 exist',
    ],
    insight:
      'HPACK made `Cookie` and `User-Agent` repeat essentially free — which quietly enabled the header-heavy API patterns we take for granted today.',
    tags: ['http', 'rfc'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2015-05-14T00:00:00Z'),
    valid_from: t('2015-05-14T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'net-tls13',
    realm: 'Networking',
    signal: 'Security',
    topic: 'TLS',
    title: 'TLS 1.3 (RFC 8446)',
    content:
      'Drastically pruned cipher suites — only AEAD ciphers, no RSA key exchange, no static DH, no renegotiation. Handshake is **1-RTT** by default, **0-RTT** on resumption (with replay caveats).\n\nForward secrecy is mandatory (ephemeral ECDHE).',
    summary:
      '1-RTT handshake, AEAD-only, mandatory forward secrecy. The cleanup TLS needed for 20 years.',
    key_points: [
      'Finalized August 2018 after ~5 years of IETF work',
      'Default suites: AES-GCM, ChaCha20-Poly1305',
      '0-RTT data MUST be replayable-safe at the app layer',
    ],
    insight:
      'Removing options was the point — TLS 1.2 had hundreds of cipher suites and most CVEs came from obscure legacy combinations (BEAST, CRIME, LUCKY13, FREAK).',
    tags: ['tls', 'crypto', 'rfc'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2018-08-10T00:00:00Z'),
    valid_from: t('2018-08-10T00:00:00Z'),
    valid_until: null,
  },

  // ── Cryptography ────────────────────────────────────────────────
  {
    id: 'crypto-aes',
    realm: 'Cryptography',
    signal: 'Symmetric',
    topic: 'Block Ciphers',
    title: 'AES (Rijndael)',
    content:
      'Daemen & Rijmen\'s Rijndael won the NIST AES competition (1997–2001), standardized as **FIPS 197**. 128-bit block; 128/192/256-bit keys giving 10/12/14 rounds. Modern CPUs have dedicated AES-NI instructions.\n\nUse authenticated modes: **GCM** for high throughput, **AES-SIV** or **AES-GCM-SIV** when nonce-reuse is a risk.',
    summary:
      'NIST-standard block cipher (FIPS 197). 128-bit block; hardware-accelerated on all modern CPUs.',
    key_points: [
      'Never use ECB except for single-block primitives',
      'GCM nonce must not repeat under the same key (catastrophic)',
      'AES-NI puts CPU-bound AES at ~1 cycle/byte on x86-64',
    ],
    insight:
      'The "fastest is safest" rule: AES-NI made GCM so cheap that weakening crypto for perf is rarely justified — and the rare device without AES-NI should use ChaCha20-Poly1305.',
    tags: ['crypto', 'symmetric', 'standard'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2001-11-26T00:00:00Z'),
    valid_from: t('2001-11-26T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'crypto-rsa',
    realm: 'Cryptography',
    signal: 'Asymmetric',
    topic: 'Public Key',
    title: 'RSA',
    content:
      'Rivest, Shamir, Adleman (1977). Security rests on the hardness of factoring n = p·q. Public key (e, n); private key (d, n) with e·d ≡ 1 (mod φ(n)).\n\n2048-bit keys are the current floor; 3072 matches AES-128 security. RSA is slow for bulk data — use it to wrap a symmetric key.',
    summary:
      'Factoring-based public-key system. Use ≥2048-bit keys; sign/encrypt symmetric keys only.',
    key_points: [
      'Always use padding: OAEP for encryption, PSS for signing — never textbook RSA',
      'Shor\'s algorithm would break RSA on a cryptographically-relevant quantum computer',
      'Being phased out in TLS in favor of ECDHE + EdDSA',
    ],
    insight:
      'RSA taught the industry that mathematical soundness ≠ implementation safety — side-channel attacks on naive implementations (Bleichenbacher 1998, ROCA 2017) are the long tail of its history.',
    tags: ['crypto', 'asymmetric', 'rsa'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('1977-01-01T00:00:00Z'),
    valid_from: t('1977-01-01T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'crypto-curve25519',
    realm: 'Cryptography',
    signal: 'Asymmetric',
    topic: 'Elliptic Curves',
    title: 'Curve25519 / X25519',
    content:
      'Bernstein\'s elliptic curve (2005) chosen for speed and resistance to implementation pitfalls. X25519 is the Diffie-Hellman function over it; Ed25519 is the EdDSA signature scheme.\n\n32-byte keys, 64-byte signatures. No "invalid curve" pitfalls. Used in TLS 1.3, SSH, Signal, WireGuard, age.',
    summary:
      'Fast, misuse-resistant elliptic curve. X25519 for key exchange, Ed25519 for signatures.',
    key_points: [
      '~128-bit security level (comparable to RSA-3072)',
      'Designed so all 32-byte strings are valid scalars',
      'Constant-time implementations are straightforward',
    ],
    insight:
      'Bernstein\'s design philosophy — eliminate footguns at the math level — is why Curve25519 ate NIST P-256\'s lunch in post-2013 protocols.',
    tags: ['crypto', 'ecc'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2005-01-01T00:00:00Z'),
    valid_from: t('2005-01-01T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'crypto-sha256',
    realm: 'Cryptography',
    signal: 'Hashing',
    topic: 'SHA-2',
    title: 'SHA-256',
    content:
      'Merkle–Damgård hash from the SHA-2 family (NSA, 2001, FIPS 180-4). 256-bit output, 512-bit block, 64 compression rounds. No published collisions; length-extension attack applies if used naively as a MAC — use HMAC-SHA-256 instead.',
    summary:
      '256-bit Merkle-Damgård hash. Collision-resistant; use HMAC for MAC, not raw SHA-256.',
    key_points: [
      '~2^128 security against collisions (birthday bound)',
      'Length-extension means `hash(key || data)` is NOT a safe MAC',
      'Bitcoin\'s proof-of-work and Git content-addressing both use it',
    ],
    insight:
      'SHA-3 (Keccak) was standardized as a backup in case SHA-2 fell — it hasn\'t, and SHA-256 still dominates everything outside niches.',
    tags: ['crypto', 'hashing'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2001-08-01T00:00:00Z'),
    valid_from: t('2001-08-01T00:00:00Z'),
    valid_until: null,
  },

  // ── Machine Learning ────────────────────────────────────────────
  {
    id: 'ml-transformer',
    realm: 'Machine Learning',
    signal: 'Architecture',
    topic: 'Attention',
    title: 'Attention Is All You Need',
    content:
      'Vaswani et al. (NeurIPS 2017) replaced recurrence with **self-attention**. Encoder-decoder stack, multi-head scaled dot-product attention, positional encodings, layer norm + residuals.\n\nKicked off BERT, GPT, T5, ViT — the entire modern LLM lineage.',
    summary:
      'The Transformer paper: self-attention replaces RNNs, enabling massive parallelism.',
    key_points: [
      'Attention(Q,K,V) = softmax(QKᵀ/√d_k)V',
      'Multi-head attention = multiple projections in parallel',
      'O(n²) sequence length cost drove flash-attention, sparse-attention, Mamba',
    ],
    insight:
      'The title was half-joke, half-thesis — but the real breakthrough was that attention parallelizes across the sequence, unlocking GPU scale that RNNs couldn\'t touch.',
    tags: ['transformer', 'ml', 'paper'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2017-06-12T00:00:00Z'),
    valid_from: t('2017-06-12T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'ml-bert',
    realm: 'Machine Learning',
    signal: 'Models',
    topic: 'Pretraining',
    title: 'BERT',
    content:
      'Devlin et al. (Google, 2018) — **Bidirectional Encoder Representations from Transformers**. Pretrained on masked-language-modeling (mask 15% of tokens, predict them) + next-sentence-prediction. Fine-tune for downstream tasks.\n\n110M (base) / 340M (large) parameters. Set SOTA on GLUE, SQuAD; popularized "pretrain + fine-tune" recipe.',
    summary:
      'Encoder-only transformer pretrained with masked LM; the "pretrain then fine-tune" template.',
    key_points: [
      'WordPiece tokenizer (30k vocab for English BERT)',
      'MLM lets attention be bidirectional — decoder LMs can\'t do this directly',
      'Distilled (DistilBERT, TinyBERT) and multilingual (mBERT, XLM-R) variants',
    ],
    insight:
      'BERT cemented the idea that you train once on oceans of unlabeled text and fine-tune on the task — which drove the "scale is enough" wave that followed.',
    tags: ['ml', 'nlp', 'transformer'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2018-10-11T00:00:00Z'),
    valid_from: t('2018-10-11T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'ml-word2vec',
    realm: 'Machine Learning',
    signal: 'Models',
    topic: 'Embeddings',
    title: 'word2vec',
    content:
      'Mikolov et al. (Google, 2013). Two architectures: **CBOW** (predict word from context) and **Skip-gram** (predict context from word). Negative sampling / hierarchical softmax make it tractable on billions of tokens.\n\nFamous "king − man + woman ≈ queen" analogies showed that offset vectors encode relations.',
    summary:
      'Shallow neural word embeddings — the first widely-used dense distributed representations.',
    key_points: [
      'Typical dim 100–300',
      'Negative sampling trains against k random non-context words',
      'GloVe (Stanford, 2014) is a matrix-factorization counterpart',
    ],
    insight:
      'The breakthrough wasn\'t the model — it was discovering that a simple objective on enough text yields structured semantic geometry, a lesson that rhymes through BERT and GPT.',
    tags: ['ml', 'nlp', 'embeddings'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2013-01-16T00:00:00Z'),
    valid_from: t('2013-01-16T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'ml-hnsw',
    realm: 'Machine Learning',
    signal: 'Retrieval',
    topic: 'ANN',
    title: 'HNSW: hierarchical navigable small world',
    content:
      'Malkov & Yashunin (2016). Builds a layered graph where upper layers are sparse long-range links, lower layers dense short-range — navigate from top to bottom, greedily hopping toward the query.\n\nQuery is O(log N) expected; recall is tunable via `efSearch`. Beats IVF-family methods on recall-vs-speed for most datasets.',
    summary:
      'Multi-layer proximity graph for approximate nearest neighbor. Log-scale queries at high recall.',
    key_points: [
      'Parameters: M (neighbors per node), efConstruction, efSearch',
      'Incremental inserts (no rebuild); deletes are tombstones',
      'Used in FAISS, pgvector, Qdrant, Weaviate, Milvus, Elasticsearch',
    ],
    insight:
      'HNSW won because it was *good enough* and simple to implement — the "small-world" math was known since Watts-Strogatz (1998); Malkov\'s contribution was making it indexable.',
    tags: ['ann', 'retrieval', 'paper'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2016-03-30T00:00:00Z'),
    valid_from: t('2016-03-30T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'ml-cosine',
    realm: 'Machine Learning',
    signal: 'Retrieval',
    topic: 'Similarity',
    title: 'Cosine similarity',
    content:
      '`cos(a,b) = a·b / (‖a‖·‖b‖)`. Angle-based — magnitude-invariant — which matters when vector norms drift with document length or training dynamics.\n\nFor unit-normalized vectors, cosine similarity and inner product are equivalent (up to sign), and Euclidean distance and cosine are monotonically related — so many engines normalize once at ingest.',
    summary:
      'Magnitude-invariant similarity. Equivalent to inner product after unit normalization.',
    key_points: [
      'Range [−1, 1]; often [0, 1] for non-negative embeddings',
      'Normalize → cheaper inner-product in index',
      'Preferred over Euclidean when embedding norms vary',
    ],
    insight:
      '"Cosine vs dot product" debates usually vanish once you decide whether to normalize — the real question is whether norm carries meaningful signal.',
    tags: ['ml', 'retrieval', 'math'],
    importance: 1,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2024-01-01T00:00:00Z'),
    valid_from: t('2024-01-01T00:00:00Z'),
    valid_until: null,
  },

  // ── Open Source ─────────────────────────────────────────────────
  {
    id: 'os-linux',
    realm: 'Open Source',
    signal: 'Kernels',
    topic: 'Linux',
    title: 'Linux kernel',
    content:
      'Linus Torvalds announced the first release on comp.os.minix on August 25, 1991. Monolithic kernel with loadable modules. GPLv2 — which Linus has repeatedly refused to relicense to v3.\n\nDevelopment moved to Git in 2005 after the BitKeeper dispute. ~30 million LOC; ~100k commits/year; ~13k contributors over its lifetime.',
    summary:
      'Monolithic GPLv2 kernel started by Torvalds in 1991; the largest open-source codebase by contributor count.',
    key_points: [
      'Releases roughly every 9–10 weeks; LTS kernels supported ~6 years',
      'Subsystem maintainer tree model with Linus pulling',
      'Rust-for-Linux merged in 6.1 (December 2022)',
    ],
    insight:
      'The maintainer tree + mailing-list workflow predates GitHub PRs by a decade — and still scales past any "modern" forge for kernel-scale projects.',
    tags: ['linux', 'kernel', 'foss'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('1991-08-25T00:00:00Z'),
    valid_from: t('1991-08-25T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'os-git',
    realm: 'Open Source',
    signal: 'Tools',
    topic: 'Version Control',
    title: 'Git',
    content:
      'Torvalds, April 2005, to replace BitKeeper for Linux kernel development. Content-addressed object store (blobs, trees, commits, tags) keyed by SHA-1 (transition to SHA-256 in progress).\n\nDistributed — every clone is a full repository. Merge via three-way merge with a common ancestor.',
    summary:
      'Content-addressed DVCS built in 10 days by Torvalds to replace BitKeeper.',
    key_points: [
      'Objects: blob (content), tree (dir), commit (snapshot+parent), tag',
      'SHAttered (2017) demonstrated SHA-1 collisions; Git added collision detection',
      'Protocol v2 (2018) improved fetch performance on huge refs',
    ],
    insight:
      'The "stupid content tracker" tagline hid the real design — a filesystem of immutable hashed objects. Everything else (branches, merges, rebases) is a pointer dance on top.',
    tags: ['git', 'tools', 'foss'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2005-04-07T00:00:00Z'),
    valid_from: t('2005-04-07T00:00:00Z'),
    valid_until: null,
  },
  {
    id: 'os-k8s',
    realm: 'Open Source',
    signal: 'Orchestration',
    topic: 'Containers',
    title: 'Kubernetes',
    content:
      'Open-sourced by Google (June 2014), inspired by internal Borg/Omega. Declarative API: you describe desired state, controllers reconcile actual → desired. Written in Go. CNCF graduated (2018).\n\nPrimitives: Pod (containers sharing a network namespace), Deployment (rollout strategy), Service (stable virtual IP), ConfigMap/Secret (config injection).',
    summary:
      'Declarative container orchestration; reconcile loops drive actual state toward desired state.',
    key_points: [
      'Control plane: API server, etcd, scheduler, controller manager',
      'etcd is the strongly-consistent source of truth (Raft under the hood)',
      'Extensibility via CRDs + operators is the real power, not the built-in primitives',
    ],
    insight:
      'Kubernetes\' gift isn\'t "containers at scale" — it\'s the reconcile-loop API pattern. Every cloud platform now mimics it, container or not.',
    tags: ['kubernetes', 'containers', 'foss'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2014-06-06T00:00:00Z'),
    valid_from: t('2014-06-06T00:00:00Z'),
    valid_until: null,
  },

  // ── Documents (scans explorer seed data) ────────────────────────
  {
    id: 'doc-contract-001',
    realm: 'documents',
    signal: 'legal',
    topic: 'Contracts',
    title: 'Service Agreement — Acme Corp 2025',
    content: 'Service agreement between Acme Corp and vendor. Terms cover SLA, payment schedule, and liability clauses. Signed and countersigned.',
    summary: 'Signed service agreement with Acme Corp covering SLA and payment terms.',
    key_points: ['SLA: 99.9% uptime', 'Payment net-30', 'Auto-renew clause'],
    insight: 'Standard contract template; auto-renew requires 60-day notice to cancel.',
    tags: ['contract', 'legal'],
    importance: 3,
    status: 'committed',
    created_by: 'scan',
    created_at: t('2025-03-15T10:00:00Z'),
    valid_from: t('2025-03-15T10:00:00Z'),
    valid_until: null,
  },
  {
    id: 'doc-invoice-001',
    realm: 'documents',
    signal: 'finance',
    topic: 'Invoices',
    title: 'Invoice #4821 — Cloud Infrastructure Q1 2025',
    content: 'Invoice from cloud provider for infrastructure services January–March 2025. Total: €12,450. Status: paid.',
    summary: 'Paid cloud infrastructure invoice for Q1 2025.',
    key_points: ['Amount: €12,450', 'Period: Jan–Mar 2025', 'Status: paid'],
    insight: 'Infrastructure costs increased 18% vs Q4 2024 due to new ML workloads.',
    tags: ['invoice', 'paid', 'cloud'],
    importance: 2,
    status: 'committed',
    created_by: 'scan',
    created_at: t('2025-04-02T09:30:00Z'),
    valid_from: t('2025-04-02T09:30:00Z'),
    valid_until: null,
  },
  {
    id: 'doc-photo-001',
    realm: 'documents',
    signal: 'media',
    topic: 'Photos',
    title: 'Office floor plan — Berlin HQ',
    content: 'Scanned floor plan of the Berlin headquarters, ground floor. Shows desk layout, meeting rooms, and emergency exits.',
    summary: 'Scanned floor plan for Berlin HQ ground floor.',
    key_points: ['Ground floor only', '32 desks', '4 meeting rooms'],
    insight: 'Layout is outdated — does not reflect the 2024 renovation.',
    tags: ['photo', 'office'],
    importance: 1,
    status: 'committed',
    created_by: 'scan',
    created_at: t('2024-08-20T14:00:00Z'),
    valid_from: t('2024-08-20T14:00:00Z'),
    valid_until: null,
  },
  {
    id: 'doc-contract-002',
    realm: 'documents',
    signal: 'legal',
    topic: 'Contracts',
    title: 'NDA — Beta Partner Program 2024',
    content: 'Non-disclosure agreement for beta testing partners. Covers confidential product features and roadmap information. Valid for 2 years.',
    summary: 'Standard NDA for beta program participants, 2-year term.',
    key_points: ['2-year term', 'Covers product roadmap', 'Mutual NDA'],
    insight: 'All beta partners must sign before accessing preview builds.',
    tags: ['contract', 'nda', 'legal'],
    importance: 2,
    status: 'committed',
    created_by: 'scan',
    created_at: t('2024-11-05T11:00:00Z'),
    valid_from: t('2024-11-05T11:00:00Z'),
    valid_until: null,
  },
  {
    id: 'doc-invoice-002',
    realm: 'documents',
    signal: 'finance',
    topic: 'Invoices',
    title: 'Invoice #3290 — Software Licenses 2024',
    content: 'Annual software license invoice for design tooling suite. Total: €8,200. Status: pending approval.',
    summary: 'Pending software license invoice for annual design tooling.',
    key_points: ['Amount: €8,200', 'Annual renewal', 'Status: pending'],
    insight: 'License count should be audited — several seats may be unused.',
    tags: ['invoice', 'software'],
    importance: 2,
    status: 'pending',
    created_by: 'scan',
    created_at: t('2024-12-01T08:00:00Z'),
    valid_from: t('2024-12-01T08:00:00Z'),
    valid_until: null,
  },
  {
    id: 'doc-other-001',
    realm: 'documents',
    signal: 'misc',
    topic: 'Other',
    title: 'Meeting minutes — Q3 2025 strategy session',
    content: 'Minutes from the Q3 2025 executive strategy session. Topics: product roadmap, hiring plan, and infrastructure migration.',
    summary: 'Q3 2025 strategy session minutes covering roadmap and hiring.',
    key_points: ['Roadmap approved', 'Headcount +5 in Q4', 'Migration to region EU-West'],
    insight: 'Infrastructure migration is the key blocker for the Q4 launch.',
    tags: ['other', 'minutes'],
    importance: 2,
    status: 'committed',
    created_by: 'scan',
    created_at: t('2025-07-10T16:00:00Z'),
    valid_from: t('2025-07-10T16:00:00Z'),
    valid_until: null,
  },
  {
    id: 'doc-contract-003',
    realm: 'documents',
    signal: 'legal',
    topic: 'Contracts',
    title: 'Lease Agreement — Server Room Munich 2025',
    content: 'Colocation lease for server rack space in Munich data centre. Monthly fee: €1,800. 3-year term.',
    summary: 'Colocation rack lease in Munich, 3-year term at €1,800/month.',
    key_points: ['3-year term', '€1,800/month', 'Redundant power included'],
    insight: 'Evaluate whether cloud egress costs make colo cheaper beyond year 2.',
    tags: ['contract', 'infrastructure'],
    importance: 3,
    status: 'pending',
    created_by: 'scan',
    created_at: t('2025-01-20T12:00:00Z'),
    valid_from: t('2025-01-20T12:00:00Z'),
    valid_until: null,
  },
  {
    id: 'doc-receipt-001',
    realm: 'documents',
    signal: 'finance',
    topic: 'Receipts',
    title: 'Travel receipts — Conf Berlin 2024',
    content: 'Scanned travel receipts for team attendance at Berlin developer conference. Total reimbursement: €640.',
    summary: 'Travel receipts for Berlin dev conference, €640 total.',
    key_points: ['Train: €210', 'Hotel: €320', 'Meals: €110'],
    insight: 'Booking earlier would cut hotel cost by ~30%.',
    tags: ['other', 'travel', 'receipt'],
    importance: 1,
    status: 'committed',
    created_by: 'scan',
    created_at: t('2024-09-18T17:30:00Z'),
    valid_from: t('2024-09-18T17:30:00Z'),
    valid_until: null,
  },
  {
    id: 'doc-invoice-003',
    realm: 'documents',
    signal: 'finance',
    topic: 'Invoices',
    title: 'Invoice #4110 — Duplicate submission (rejected)',
    content: 'Duplicate invoice submission for an already-paid order; rejected during review.',
    summary: 'Duplicate invoice, rejected — original already paid.',
    key_points: ['Duplicate of invoice #4098', 'Rejected on review'],
    insight: 'Vendor should be asked to stop resending paid invoices.',
    tags: ['invoice'],
    importance: 1,
    status: 'rejected',
    created_by: 'scan',
    created_at: t('2025-02-14T10:00:00Z'),
    valid_from: t('2025-02-14T10:00:00Z'),
    valid_until: null,
  },
]

const realms: Realm[] = (() => {
  const map = new Map<string, Map<string, number>>()
  for (const c of cells) {
    if (!map.has(c.realm)) map.set(c.realm, new Map())
    const sm = map.get(c.realm)!
    const s = c.signal ?? '(none)'
    sm.set(s, (sm.get(s) ?? 0) + 1)
  }
  return [...map.entries()].map(([name, sm]) => ({
    name,
    cell_count: [...sm.values()].reduce((a, b) => a + b, 0),
    signals: [...sm.entries()].map(([sn, sc]) => ({ name: sn, cell_count: sc, topics: [] })),
  }))
})()

const tunnels: Tunnel[] = [
  { id: 'tun-1', from_cell: 'ds-raft',       to_cell: 'ds-paxos',     relation: 'builds_on',   note: 'Raft designed for understandability vs Paxos', status: 'committed', created_at: t('2014-10-01T00:00:00Z'), valid_until: null },
  { id: 'tun-2', from_cell: 'ds-raft',       to_cell: 'os-k8s',       relation: 'related_to',  note: 'etcd (Raft) is the K8s source of truth',       status: 'committed', created_at: t('2018-01-01T00:00:00Z'), valid_until: null },
  { id: 'tun-3', from_cell: 'ds-dynamo',     to_cell: 'ds-cap',       relation: 'builds_on',   note: 'Dynamo chose AP during partitions',           status: 'committed', created_at: t('2007-10-14T00:00:00Z'), valid_until: null },
  { id: 'tun-4', from_cell: 'ds-mapreduce',  to_cell: 'ds-dynamo',    relation: 'related_to',  note: 'Contemporary Google/Amazon distributed papers', status: 'committed', created_at: t('2007-10-14T00:00:00Z'), valid_until: null },
  { id: 'tun-5', from_cell: 'db-pgvector',   to_cell: 'ml-hnsw',      relation: 'builds_on',   note: 'pgvector ships HNSW as its primary index',     status: 'committed', created_at: t('2021-04-20T00:00:00Z'), valid_until: null },
  { id: 'tun-6', from_cell: 'db-pgvector',   to_cell: 'ml-cosine',    relation: 'related_to',  note: 'pgvector <=> operator is cosine distance',     status: 'committed', created_at: t('2021-04-20T00:00:00Z'), valid_until: null },
  { id: 'tun-7', from_cell: 'db-mvcc',       to_cell: 'db-wal',       relation: 'related_to',  note: 'MVCC depends on WAL for durability',           status: 'committed', created_at: t('2023-01-01T00:00:00Z'), valid_until: null },
  { id: 'tun-8', from_cell: 'ml-bert',       to_cell: 'ml-transformer', relation: 'builds_on', note: 'BERT is a Transformer encoder',                status: 'committed', created_at: t('2018-10-11T00:00:00Z'), valid_until: null },
  { id: 'tun-9', from_cell: 'ml-word2vec',   to_cell: 'ml-transformer', relation: 'related_to', note: 'Both produce dense distributed representations', status: 'committed', created_at: t('2017-06-12T00:00:00Z'), valid_until: null },
  { id: 'tun-10', from_cell: 'net-quic',     to_cell: 'net-tls13',    relation: 'builds_on',   note: 'QUIC embeds TLS 1.3',                          status: 'committed', created_at: t('2021-05-27T00:00:00Z'), valid_until: null },
  { id: 'tun-11', from_cell: 'net-http2',    to_cell: 'net-tcp',      relation: 'related_to',  note: 'HTTP/2 still suffers TCP HoL',                 status: 'committed', created_at: t('2015-05-14T00:00:00Z'), valid_until: null },
  { id: 'tun-12', from_cell: 'net-quic',     to_cell: 'net-http2',    relation: 'refines',     note: 'HTTP/3 over QUIC fixes HTTP/2\'s HoL issue',    status: 'committed', created_at: t('2022-06-06T00:00:00Z'), valid_until: null },
  { id: 'tun-13', from_cell: 'crypto-aes',   to_cell: 'net-tls13',    relation: 'related_to',  note: 'TLS 1.3 AEAD ciphers: AES-GCM + ChaCha20',     status: 'committed', created_at: t('2018-08-10T00:00:00Z'), valid_until: null },
  { id: 'tun-14', from_cell: 'crypto-curve25519', to_cell: 'net-tls13', relation: 'related_to', note: 'X25519 is a primary key-exchange group',      status: 'committed', created_at: t('2018-08-10T00:00:00Z'), valid_until: null },
  { id: 'tun-15', from_cell: 'crypto-rsa',   to_cell: 'crypto-curve25519', relation: 'contradicts', note: 'ECC replacing RSA in modern TLS',          status: 'committed', created_at: t('2020-01-01T00:00:00Z'), valid_until: null },
  { id: 'tun-16', from_cell: 'crypto-sha256', to_cell: 'os-git',      relation: 'related_to',  note: 'Git content addressing moving SHA-1 → SHA-256', status: 'committed', created_at: t('2017-02-23T00:00:00Z'), valid_until: null },
  { id: 'tun-17', from_cell: 'os-k8s',       to_cell: 'os-linux',     relation: 'builds_on',   note: 'Containers are a Linux cgroups + namespaces feature', status: 'committed', created_at: t('2014-06-06T00:00:00Z'), valid_until: null },
  { id: 'tun-18', from_cell: 'os-git',       to_cell: 'os-linux',     relation: 'builds_on',   note: 'Git written by Torvalds for Linux kernel dev',  status: 'committed', created_at: t('2005-04-07T00:00:00Z'), valid_until: null },
  { id: 'tun-19', from_cell: 'db-btree-lsm', to_cell: 'db-redis',     relation: 'related_to',  note: 'Redis is in-memory; different tradeoffs from on-disk B-tree/LSM', status: 'committed', created_at: t('2024-01-01T00:00:00Z'), valid_until: null },
  { id: 'tun-20', from_cell: 'ml-cosine',    to_cell: 'ml-word2vec',  relation: 'related_to',  note: 'word2vec embeddings typically compared by cosine', status: 'committed', created_at: t('2013-01-16T00:00:00Z'), valid_until: null },
]

const facts: Fact[] = [
  { id: 'f-1', subject: 'Raft',         predicate: 'authored_by',   object: 'Diego Ongaro, John Ousterhout (Stanford)', valid_from: t('2014-10-01T00:00:00Z'), valid_until: null },
  { id: 'f-2', subject: 'Raft',         predicate: 'published_at',  object: 'USENIX ATC 2014',                           valid_from: t('2014-10-01T00:00:00Z'), valid_until: null },
  { id: 'f-3', subject: 'MapReduce',    predicate: 'authored_by',   object: 'Jeff Dean, Sanjay Ghemawat (Google)',       valid_from: t('2004-12-06T00:00:00Z'), valid_until: null },
  { id: 'f-4', subject: 'Transformer',  predicate: 'introduced_by', object: '"Attention Is All You Need" (NeurIPS 2017)', valid_from: t('2017-06-12T00:00:00Z'), valid_until: null },
  { id: 'f-5', subject: 'AES',          predicate: 'standardized_as', object: 'NIST FIPS 197 (2001)',                    valid_from: t('2001-11-26T00:00:00Z'), valid_until: null },
  { id: 'f-6', subject: 'QUIC',         predicate: 'standardized_as', object: 'IETF RFC 9000 (May 2021)',                valid_from: t('2021-05-27T00:00:00Z'), valid_until: null },
  { id: 'f-7', subject: 'HTTP/3',       predicate: 'standardized_as', object: 'IETF RFC 9114 (June 2022)',               valid_from: t('2022-06-06T00:00:00Z'), valid_until: null },
  { id: 'f-8', subject: 'TLS 1.3',      predicate: 'standardized_as', object: 'IETF RFC 8446 (August 2018)',             valid_from: t('2018-08-10T00:00:00Z'), valid_until: null },
  { id: 'f-9', subject: 'Redis',        predicate: 'default_port',  object: '6379/tcp',                                   valid_from: t('2009-05-10T00:00:00Z'), valid_until: null },
  { id: 'f-10', subject: 'Linux kernel', predicate: 'license',      object: 'GPL-2.0-only',                               valid_from: t('1992-01-01T00:00:00Z'), valid_until: null },
  { id: 'f-11', subject: 'Linux kernel', predicate: 'first_released', object: '1991-08-25 (announce on comp.os.minix)',   valid_from: t('1991-08-25T00:00:00Z'), valid_until: null },
  { id: 'f-12', subject: 'Git',         predicate: 'first_commit',  object: '2005-04-07 by Linus Torvalds',               valid_from: t('2005-04-07T00:00:00Z'), valid_until: null },
  { id: 'f-13', subject: 'Kubernetes',  predicate: 'open_sourced_by', object: 'Google, June 2014',                        valid_from: t('2014-06-06T00:00:00Z'), valid_until: null },
  { id: 'f-14', subject: 'PostgreSQL',  predicate: 'concurrency_model', object: 'MVCC (xmin/xmax per tuple)',             valid_from: t('1996-01-01T00:00:00Z'), valid_until: null },
  { id: 'f-15', subject: 'BERT-base',   predicate: 'parameter_count', object: '110M',                                     valid_from: t('2018-10-11T00:00:00Z'), valid_until: null },
]

const references: Reference[] = [
  { id: 'r-raft',        title: 'In Search of an Understandable Consensus Algorithm (Raft)',       url: 'https://raft.github.io/raft.pdf',                                           ref_type: 'paper',   status: 'done'    },
  { id: 'r-paxos',       title: 'The Part-Time Parliament (Paxos)',                                 url: 'https://lamport.azurewebsites.net/pubs/lamport-paxos.pdf',                  ref_type: 'paper',   status: 'done'    },
  { id: 'r-mapreduce',   title: 'MapReduce: Simplified Data Processing on Large Clusters',           url: 'https://static.googleusercontent.com/media/research.google.com/en//archive/mapreduce-osdi04.pdf', ref_type: 'paper', status: 'done' },
  { id: 'r-dynamo',      title: 'Dynamo: Amazon\'s Highly Available Key-value Store',                url: 'https://www.allthingsdistributed.com/files/amazon-dynamo-sosp2007.pdf',    ref_type: 'paper',   status: 'done'    },
  { id: 'r-attention',   title: 'Attention Is All You Need',                                         url: 'https://arxiv.org/abs/1706.03762',                                         ref_type: 'paper',   status: 'done'    },
  { id: 'r-bert',        title: 'BERT: Pre-training of Deep Bidirectional Transformers',             url: 'https://arxiv.org/abs/1810.04805',                                         ref_type: 'paper',   status: 'done'    },
  { id: 'r-hnsw',        title: 'Efficient and robust approximate nearest neighbor search using HNSW', url: 'https://arxiv.org/abs/1603.09320',                                       ref_type: 'paper',   status: 'done'    },
  { id: 'r-rfc9000',     title: 'RFC 9000 — QUIC: A UDP-Based Multiplexed and Secure Transport',      url: 'https://datatracker.ietf.org/doc/html/rfc9000',                            ref_type: 'article', status: 'done'    },
  { id: 'r-rfc9114',     title: 'RFC 9114 — HTTP/3',                                                 url: 'https://datatracker.ietf.org/doc/html/rfc9114',                            ref_type: 'article', status: 'done'    },
  { id: 'r-rfc8446',     title: 'RFC 8446 — The Transport Layer Security (TLS) Protocol Version 1.3', url: 'https://datatracker.ietf.org/doc/html/rfc8446',                            ref_type: 'article', status: 'done'    },
  { id: 'r-fips197',     title: 'FIPS 197 — Advanced Encryption Standard (AES)',                      url: 'https://csrc.nist.gov/publications/detail/fips/197/final',                 ref_type: 'article', status: 'done'    },
  { id: 'r-pgvector',    title: 'pgvector (GitHub)',                                                 url: 'https://github.com/pgvector/pgvector',                                      ref_type: 'article', status: 'done'    },
  { id: 'r-postgresmvcc', title: 'PostgreSQL Docs — Concurrency Control (MVCC)',                     url: 'https://www.postgresql.org/docs/current/mvcc.html',                        ref_type: 'article', status: 'done'    },
  { id: 'r-redis',       title: 'Redis Documentation',                                               url: 'https://redis.io/docs/',                                                   ref_type: 'article', status: 'done'    },
  { id: 'r-kube',        title: 'Kubernetes: The Documentary',                                       url: 'https://www.youtube.com/watch?v=BE77h7dmoQU',                              ref_type: 'article', status: 'reading' },
  { id: 'r-linus-git',   title: 'Linus Torvalds on Git (2007 Google Tech Talk)',                     url: 'https://www.youtube.com/watch?v=4XpnKHJAok8',                              ref_type: 'article', status: 'reading' },
]

export const palace = { cells, realms, tunnels, facts, references }
