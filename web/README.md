# POI Capture Reviewer (web app)

Stage 3 of the pipeline (docs/pipeline.md): confirm what the agentic processing found, then publish.

- **Import**: a processed session folder (with `result.json`, from the `process-sweep` skill), a zip of it, or a zip
  exported from the phone (raw: ride, photos, places with the contributor's pins). Drop it anywhere on the page.
  Everything stays in this browser (IndexedDB); nothing is uploaded, and there is no sign-in.
- **Review** each place: photos, every field read from them (with the photo it came from), three pins:
  algorithm or contributor (green), agent (purple), Google (blue, for checking only), and the final pin (orange,
  drag it to the doorway). Confirm, needs a visit, or reject; fields can be confirmed, edited or rejected one by one.
- **Links**: every view has its own address to share: `#/s/<session>`, `#/s/<session>/p/<place>`,
  `#/s/<session>/t/<task>`, `#/tasks`. A link opens only where that session was imported (there is no server);
  otherwise the app says which session to import.
- **Tasks**: visits for Place capture, removals to confirm or check on site; export as GeoJSON.
- **Publish**: confirmed places and removals as GeoJSON and CSV, new places as an OpenStreetMap `.osm` file for JOSM.
  Google's details and pins are never written.

```
npm install
npm run dev      # http://localhost:5173
npm test
npm run build    # dist/, deployed to Cloudflare Pages (poi-capture-reviewer) by .github/workflows/web.yml
```

Map tiles: OpenStreetMap (the app is served over http(s), as the OSM tile policy needs), Esri satellite as a layer.
