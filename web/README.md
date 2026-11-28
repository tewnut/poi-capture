# Quét Phố Reviewer (web app)

Formerly POI Capture Reviewer.

Stage 3 of the pipeline (docs/pipeline.md): confirm what the agentic processing found, then publish.

- **Sessions** (left panel, the only list): stage chips with counts (All · Captured · Processing · To review · In
  review · Reviewed / exported; one tap filters, the counts follow the other filters), a search over name, street, rider
  and building that ignores accents ("dong van cong" finds "Đồng Văn Cống"), and under **Filter** the target (places,
  addresses, signs, indoor, Place capture), the date (today, 7 or 30 days), the rider and the sort (newest, oldest,
  stage, name). Sorted by date the rows are grouped by day. The search, filters and sort are in the address
  (`#/?q=…&stage=…`), so a filtered list can be shared. The map shows the rides listed; hovering a row draws its route
  bold, a click on a route picks its row (a second click opens it). Each row keeps its stage as three dots, Captured →
  Agent processed → Human reviewed, and its review progress. Everything stays in this browser (IndexedDB).
- **A session**: its **Places**, and under them a folding **To do (N)** section with its tasks (visits for Place
  capture, removals to confirm; export as GeoJSON). Review a place: photos (full screen, or open in a new tab), every
  field read from them (with the photo it came from), the positions as chips (Algo or contributor, Agent, your
  Pin, Google for checking only), then Confirm, Needs a visit or Reject.
- **Settings** (⚙ at the top): your reviewer name and the language (English / Tiếng Việt).
- **Google** (decided 2026-11-15, docs/pipeline.md): fields the photos lack are filled from Google and marked; a field
  that matches or differs from Google is labelled on the review screen only. Labels never reach the published data;
  Google-filled fields are listed in `fromGoogle` (GeoJSON / CSV) and left out of the `.osm` file.
- **Clear all data** (⚙): removes every session, photo and review from this browser, to import fresh sessions.
- **Publish** (in a session): that session's confirmed places and removals as GeoJSON and CSV, new places as an
  OpenStreetMap `.osm` file for JOSM. Google's details and pins are never written.
- **Links**: `#/` sessions (with `?q=&stage=&kind=&period=&rider=&sort=`), `#/s/<session>` its places and tasks,
  `#/s/<session>/p/<place>`, `#/s/<session>/t/<task>`; older links still work: `#/tasks` lands on the session list,
  `#/s/<session>/tasks` opens the session with its to-do section open. A link opens only where that session was imported (there
  is no server); otherwise the app says which session to import.

```
npm install
npm run dev      # http://localhost:5173
npm test
npm run build    # dist/, deployed to Cloudflare Pages (poi-capture-reviewer) by .github/workflows/web.yml
```

Map tiles: OpenStreetMap (the app is served over http(s), as the OSM tile policy needs), Esri satellite as a layer.
