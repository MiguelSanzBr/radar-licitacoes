| Consulta | Antes (ms) | Depois (ms) | Buffers antes | Buffers depois | Nó antes | Nó depois |
| --- | ---: | ---: | --- | --- | --- | --- |
| `s1-text-common` | 1774.448 | 602.842 | acertos=150189 leituras=117108 written=542 | acertos=161028 leituras=106269 | Parallel Seq Scan | Parallel Seq Scan |
| `s1-text-rare` | 857.294 | 6.584 | acertos=105726 leituras=115380 | acertos=949 | Parallel Seq Scan | Bitmap Heap Scan |
| `s2-text-filters` | 419.739 | 71.459 | acertos=104557 leituras=116637 written=696 | acertos=53078 | Parallel Seq Scan | Index Scan using psv_open_closing |
| `s3-keyset-deep` | 792.531 | 0.104 | acertos=22613 leituras=113181 | acertos=9 | Parallel Seq Scan | Index Scan using psv_published_keyset |
| `s3-keyset-first` | 938.277 | 0.115 | acertos=24403 leituras=111453 | acertos=12 | Parallel Seq Scan | Index Scan using psv_published_keyset |
| `s3-offset-deep` | 3300.339 | 37.743 | acertos=26131 leituras=109725, temp read=63991 written=123822 | acertos=5755 | Parallel Seq Scan | Index Scan using psv_published_keyset |
| `s4-organ-typo` | 2264.713 | 2038.718 | acertos=27784 leituras=107997 | acertos=31965 leituras=103816 | Parallel Seq Scan | Parallel Seq Scan |
| `s5-detail` | 0.061 | 0.110 | acertos=4 | acertos=4 | Index Scan using procurement_detail_view_pkey | Index Scan using procurement_detail_view_pkey |
| `s6-stats` | 0.838 | 0.553 | acertos=16 | acertos=16 | Seq Scan | Seq Scan |
