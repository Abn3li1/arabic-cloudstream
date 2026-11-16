# CinemaHome عربي (CloudStream extensions)

Two CloudStream extensions for Arabic content. They run on the viewer's device; no server and no TMDB.

| Extension | Site | Rows |
|---|---|---|
| `MovizLand` | moviz.land (WordPress) | عراقي، رمضان 2026، عربي، مصري، تركي مترجم/مدبلج، كوري، آسيوي، أفلام عربية/تركية/كورية/آسيوية/أجنبية |
| `Brstej` | brstej1.com (برستيج) | عراقي، رمضان 2026، عربي، خليجي، شامي، مصري، تركي، آسيوي وكوري، برامج، أفلام |

Install in CloudStream: Settings → Extensions → Add repository →
`https://raw.githubusercontent.com/Abn3li1/arabic-cloudstream/main/repo.json`

## How it works
- **MovizLand**: rows from `/category/<path>/page/N/`, search through `/wp-json/wp/v2/posts?search=`, seasons from the
  `/series/<name>-الموسم-N/` pages each episode links to, servers from `<post>/watch/` (`button[data-src]`).
- **Brstej**: rows from `cat03.php?cat=<slug>&page=N`, search through `search.php?keywords=` (falls back to single words,
  because the site matches stored words only), episodes from `series1.php?id=` (`.pds-caption`), servers from
  `play.php?vid=` (`[data-embed-url]`).
- Servers go to CloudStream's own extractors first (ok.ru, vidmoly, voe, uqload...). Hosts it does not know but that use
  the common packed player (hdup400, film77, 1vid, vidoba, mp4plus) are read by `packedPlayer`.
- **عراقي** row: neither site has an Iraqi section, so the row searches the names in `IRAQI_TITLES` (one list per
  provider). Add new Iraqi series there.

## Build and ship
`./gradlew make` builds `*/build/*.cs3`. Raise `version` in the extension's `build.gradle.kts` for every change; pushing
to `main` makes the GitHub workflow build and publish to the `builds` branch, and installs update themselves.
If a site moves to a new domain (brstej1 → brstej2), change `mainUrl`.
