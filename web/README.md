# POI Capture Reviewer (web app)

Stage 3 of the pipeline (docs/pipeline.md): confirm what the agentic processing found, then publish.

- **Sessions** (left panel): every imported session with its stage as three dots, Captured → Agent processed → Human
  reviewed (the name on hover), and review progress; filter by stage, kind, mounted / in hand. Import a processed session folder
  (with `result.json`, from the `process-sweep` skill), a zip of it, or a zip exported from the phone, with the
  buttons under the list or by dropping it on the page. Everything stays in this browser (IndexedDB); nothing is
  uploaded, and there is no sign-in.
- **A session**: its **Places** and **Tasks**. Review a place: photos (full screen, or open in a new tab), every
  field read from them (with the photo it came from), the positions as chips (Algo or contributor, Agent, your
  Pin, Google for checking only), then Confirm, Needs a visit or Reject.
- **All tasks**: visits for Place capture and removals across the sessions shown, grouped by session, to plan
  field visits; export as GeoJSON.
- **Settings** (⚙ at the top): your reviewer name and the language (English / Tiếng Việt).
- **Google** (decided 2026-11-15, docs/pipeline.md): fields the photos lack are filled from Google and marked; a field
  that matches or differs from Google is labelled on the review screen only. Labels never reach the published data;
  Google-filled fields are listed in `fromGoogle` (GeoJSON / CSV) and left out of the `.osm` file.
- **Clear all data** (⚙): removes every session, photo and review from this browser, to import fresh sessions.
- **Publish** (in a session): that session's confirmed places and removals as GeoJSON and CSV, new places as an
  OpenStreetMap `.osm` file for JOSM. Google's details and pins are never written.
- **Links**: `#/` sessions, `#/tasks` all tasks, `#/s/<session>` its places, `#/s/<session>/tasks`,
  `#/s/<session>/p/<place>`, `#/s/<session>/t/<task>`. A link opens only where that session was imported (there
  is no server); otherwise the app says which session to import.

```
npm install
npm run dev      # http://localhost:5173
npm test
npm run build    # dist/, deployed to Cloudflare Pages (poi-capture-reviewer) by .github/workflows/web.yml
```

Map tiles: OpenStreetMap (the app is served over http(s), as the OSM tile policy needs), Esri satellite as a layer.
