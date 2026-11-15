"""Map views for processed sweeps: the map on a sweep's index.html, and a hub page over all of them.

    python mapview.py <root>     (re)write <root>/index.html: every processed session under <root> on one map,
                                 with a session list and filters

A processed session is a folder holding place folders (NN_name/place.json); sweep.json (written by build.py since
the map view) adds the ride track and the photo positions. Leaflet from cdnjs; Esri street / satellite tiles, since
the page is opened from disk: OpenStreetMap's tile servers answer 403 without a website referer (tile usage policy)
and Carto's basemaps "API KEY REQUIRED" (2026). The data is embedded in the page: a page opened from
disk cannot load separate JSON files.
"""
import html, json, os, sys
from datetime import datetime, timezone

LEAFLET = ('<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css" '
           'integrity="sha512-h9FcoyWjHcOcmEVkxOfTLnmZFWIH0iZhZT1H2TbOq55xssQGEJHEaIm+PgoUaZbRvQTNTluNOEfb1ZRy6D3BOw==" '
           'crossorigin="anonymous" referrerpolicy="no-referrer">\n'
           '<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js" '
           'integrity="sha512-puJW3E/qXDqYp9IfhAI54BJEaWIfloJ7JWs7OeD5i6ruC9JZL1gERT1wjtwXFlh7CjE7ZJ+/vcRZRkIYIb6p4g==" '
           'crossorigin="anonymous" referrerpolicy="no-referrer"></script>')


def script_json(data):
    """JSON safe inside <script>: no '</script>' or HTML comment can end the script early."""
    return (json.dumps(data, ensure_ascii=False)
            .replace('<', '\\u003c').replace('>', '\\u003e').replace('&', '\\u0026')
            .replace('\u2028', '\\u2028').replace('\u2029', '\\u2029'))


def median(v):
    v = sorted(v)
    return v[len(v) // 2] if v else None


def session_data(folder, rel=''):
    """Everything the map draws for one processed session; None when the folder holds no places."""
    places = []
    for d in sorted(os.listdir(folder)):
        f = os.path.join(folder, d, 'place.json')
        if not os.path.isfile(f):
            continue
        m = json.load(open(f, encoding='utf-8'))
        p, pos, gm = m['place'], m['place']['position'], m.get('googleMaps') or {}
        cover = next((x for x in m['photos'] if x.get('cover')), m['photos'][0] if m['photos'] else None)
        truth = m.get('groundTruth')
        places.append({
            'folder': d, 'n': d[:2], 'name': p['name'], 'category': p.get('category'), 'confidence': p.get('confidence'),
            'lat': pos['lat'], 'lon': pos['lon'], 'method': pos.get('method'), 'unc': pos.get('uncertaintyM'),
            'sightings': pos.get('sightings') or [],
            'cover': f"{d}/{cover['file']}" if cover else None,
            'photos': [f"{d}/{x['file']}" for x in m['photos']],
            'cameras': [{'lat': x['camera']['lat'], 'lon': x['camera']['lon'], 'i': x.get('sweepIndex')}
                        for x in m['photos'] if x['camera'].get('lat') is not None],
            'review': (m.get('review') or {}).get('status'),
            'google': {k: gm.get(k) for k in ('name', 'address', 'phone', 'website', 'url', 'lat', 'lon', 'distanceM',
                                                'status', 'category', 'rating', 'hoursSummary')} if gm else None,
            'truth': truth,
        })
    if not places:
        return None
    sw = {}
    if os.path.isfile(os.path.join(folder, 'sweep.json')):
        sw = json.load(open(os.path.join(folder, 'sweep.json'), encoding='utf-8'))
    first = json.load(open(os.path.join(folder, places[0]['folder'], 'place.json'), encoding='utf-8'))
    ses = sw.get('session') or first.get('session') or {}
    t0 = ses.get('startedAt') or next((x.get('t') for x in first.get('photos') or [] if x.get('t')), None)
    errs = [p['truth']['errorM'] for p in places if p.get('truth') and p['truth'].get('errorM') is not None]
    return {
        'id': ses.get('id'), 'name': ses.get('name') or os.path.basename(folder), 'kind': ses.get('kind') or 'sweep',
        'holding': ses.get('holding') or ('mount' if (ses.get('kind') or 'sweep') == 'sweep' else None),
        'date': (t0 or '')[:10], 'started': t0, 'rel': rel, 'street': sw.get('street'),
        'track': sw.get('track') or [], 'shots': sw.get('photos') or [],
        'places': places, 'medianErrorM': median(errs), 'doors': len(errs),
    }


CSS = """
:root{--ink:#1d2621;--muted:#66736c;--line:#e3e8e5;--canvas:#f3f6f4;--card:#fff;--accent:#1f7a52;--found:#1659c7;--new:#e8590c;--todo:#8a949c;--door:#16a34a;--err:#d6336c}
*{box-sizing:border-box}body{margin:0;font:14px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:var(--ink);background:var(--canvas)}
a{color:var(--found)}h1{font-size:20px;margin:0 0 2px;letter-spacing:-.3px}.muted{color:var(--muted)}
.pin{width:26px;height:26px;border-radius:50%;color:#fff;font:700 11px/22px system-ui;text-align:center;border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.35)}
.pin.low{border-style:dashed;border-color:#222}.pin.sel{transform:scale(1.35);box-shadow:0 0 0 4px rgba(31,122,82,.35)}
.door{width:12px;height:12px;background:var(--door);border:2px solid #fff;box-shadow:0 1px 3px rgba(0,0,0,.4);transform:rotate(45deg)}
.legend{background:rgba(255,255,255,.94);padding:6px 9px;border-radius:8px;font-size:12px;line-height:1.7;box-shadow:0 1px 4px rgba(0,0,0,.2)}
.legend i{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:5px;vertical-align:-1px}
.chip{display:inline-block;font-size:11px;padding:1px 7px;border-radius:9px;background:#e7f6ec;color:#1b5e3c;margin-left:4px;font-weight:600;vertical-align:2px}
.chip.low{background:#fdecea;color:#9b1c1c}.chip.new{background:#fff0e6;color:#a23b05}.chip.found{background:#e8f0fe;color:#123f8c}.chip.todo{background:#eef0f2;color:#555}
.leaflet-popup-content{margin:10px 12px;font:13px/1.4 system-ui,sans-serif}.pop img{width:240px;max-width:100%;border-radius:6px;display:block;margin:6px 0}
.pop b{font-size:14px}
"""

JS = r"""
const COL={found:'#1659c7',new:'#e8590c',todo:'#8a949c'};
function gstat(p){if(!p.google)return 'todo';if(p.google.name)return 'found';return p.google.status?'new':'todo'}
const GLABEL={found:'on Google Maps',new:'not on Google Maps nearby',todo:'Google not looked up'};
function enc(path){return path.split('/').map(encodeURIComponent).join('/')}
function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
function makeMap(el){
  const m=L.map(el,{maxZoom:21,zoomSnap:.5});
  // Esri tiles: the page is opened from disk, and OpenStreetMap's tile servers refuse requests without a website
  // referer (403, tile usage policy). OSM stays in the layer list for when the pages are served over http(s).
  const esri=n=>'https://server.arcgisonline.com/ArcGIS/rest/services/'+n+'/MapServer/tile/{z}/{y}/{x}';
  const streets=L.tileLayer(esri('World_Street_Map'),{maxNativeZoom:19,maxZoom:21,attribution:'Tiles &copy; Esri'}),
    sat=L.tileLayer(esri('World_Imagery'),{maxNativeZoom:19,maxZoom:21,attribution:'Imagery &copy; Esri, Maxar, Earthstar Geographics'}),
    osm=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxNativeZoom:19,maxZoom:21,
      attribution:'&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'});
  streets.addTo(m);
  L.control.layers({'Streets (Esri)':streets,'Satellite (Esri)':sat,
    ['OpenStreetMap'+(location.protocol==='file:'?' (blocked when opened from disk)':'')]:osm},null,{position:'topright'}).addTo(m);
  const lg=L.control({position:'bottomleft'});
  lg.onAdd=()=>{const d=L.DomUtil.create('div','legend');d.innerHTML=
    '<i style="background:'+COL.found+'"></i>on Google Maps<br><i style="background:'+COL.new+'"></i>not on Google nearby<br>'+
    '<i style="background:'+COL.todo+'"></i>Google not looked up<br><i style="background:#16a34a;border-radius:0;transform:rotate(45deg)"></i>door captured on site<br>'+
    '<i style="background:#7d8c84;height:4px;border-radius:2px"></i>ride, dots = photos';return d};
  lg.addTo(m);return m}
function pinIcon(p,sel){return L.divIcon({className:'',iconSize:[26,26],iconAnchor:[13,13],
  html:'<div class="pin '+(p.confidence==='low'?'low ':'')+(sel?'sel':'')+'" style="background:'+COL[gstat(p)]+'">'+esc(p.n)+'</div>'})}
const doorIcon=L.divIcon({className:'',iconSize:[12,12],iconAnchor:[6,6],html:'<div class="door"></div>'});
function drawRide(m,s,g){
  if(s.track.length>1)L.polyline(s.track,{color:'#7d8c84',weight:4,opacity:.55}).addTo(g);
  for(const p of s.shots)L.circleMarker([p[1],p[2]],{radius:2.2,color:'#4b5a52',weight:1,fillOpacity:.8})
    .bindTooltip('photo #'+p[0]+(p[3]!=null?' · camera '+Math.round(p[3])+'°':'')).addTo(g)}
function popup(p,base,link){
  const g=p.google||{},st=gstat(p);let h='<div class="pop"><b>'+esc(p.n)+' · '+esc(p.name)+'</b>'+
    (p.confidence==='low'?'<span class="chip low">low</span>':'')+'<br><span class="muted">'+esc(p.category||'')+'</span>';
  if(p.cover)h+='<img src="'+enc(base+p.cover)+'" loading="lazy">';
  h+='<span class="chip '+st+'" style="margin:0">'+GLABEL[st]+'</span>';
  if(g.name)h+='<br>'+esc(g.name)+(g.distanceM!=null?' <span class="muted">('+g.distanceM+' m)</span>':'')+(g.address?'<br><span class="muted">'+esc(g.address)+'</span>':'');
  if(p.truth&&p.truth.errorM!=null)h+='<br>door captured on site: <b>'+p.truth.errorM+' m</b> from the estimate';
  h+='<br><span class="muted">'+esc(p.method||'')+(p.unc!=null?', ±'+p.unc+' m':'')+'</span>';
  if(link)h+='<br><a href="'+link+'">open in the session report →</a>';
  return h+'</div>'}
function drawPlace(p,g,sel){
  if(!sel)return;
  for(const s of p.sightings)L.polyline([[s.lat,s.lon],[p.lat,p.lon]],{color:'#1f7a52',weight:2,opacity:.9}).addTo(g),
    L.circleMarker([s.lat,s.lon],{radius:4,color:'#1f7a52',fillColor:'#fff',fillOpacity:1,weight:2}).bindTooltip('photo #'+s.i).addTo(g);
  if(!p.sightings.length)for(const c of p.cameras)L.polyline([[c.lat,c.lon],[p.lat,p.lon]],{color:'#1f7a52',weight:2,dashArray:'4 4'}).addTo(g);
  if(p.unc)L.circle([p.lat,p.lon],{radius:p.unc,color:'#1f7a52',weight:1,fillOpacity:.06}).addTo(g);
  const gm=p.google;
  if(gm&&gm.lat!=null){L.circleMarker([gm.lat,gm.lon],{radius:6,color:COL.found,weight:2,fillOpacity:.15}).bindTooltip('Google: '+esc(gm.name)).addTo(g);
    L.polyline([[p.lat,p.lon],[gm.lat,gm.lon]],{color:COL.found,weight:1.5,dashArray:'2 5'}).addTo(g)}}
function drawTruth(p,g){
  if(!p.truth)return;L.marker([p.truth.lat,p.truth.lon],{icon:doorIcon}).bindTooltip('door '+esc(p.truth.label||'')+' · '+esc(p.name)).addTo(g);
  L.polyline([[p.lat,p.lon],[p.truth.lat,p.truth.lon]],{color:'#d6336c',weight:2,dashArray:'5 4'})
    .bindTooltip(p.truth.errorM+' m',{permanent:false}).addTo(g)}
function bounds(ss){const b=[];for(const s of ss){for(const t of s.track)b.push(t);for(const p of s.places){b.push([p.lat,p.lon]);if(p.truth)b.push([p.truth.lat,p.truth.lon])}}return b}

/* one session: map beside the place cards */
function initSession(S){
  const m=makeMap('map'),base=L.layerGroup().addTo(m),selg=L.layerGroup().addTo(m),marks={};let cur=null;
  drawRide(m,S,base);for(const p of S.places)drawTruth(p,base);
  function select(p,fly){cur=p;selg.clearLayers();drawPlace(p,selg,true);
    for(const q of S.places)marks[q.folder].setIcon(pinIcon(q,q===p));
    document.querySelectorAll('section.place').forEach(e=>e.classList.toggle('on',e.id===p.folder));
    if(fly){const b=[[p.lat,p.lon],...p.sightings.map(s=>[s.lat,s.lon])];if(p.truth)b.push([p.truth.lat,p.truth.lon]);m.fitBounds(b,{padding:[60,60],maxZoom:20})}}
  for(const p of S.places){marks[p.folder]=L.marker([p.lat,p.lon],{icon:pinIcon(p,false),zIndexOffset:500}).addTo(m)
    .bindTooltip(esc(p.n)+' · '+esc(p.name)).on('click',()=>{select(p,false);document.getElementById(p.folder).scrollIntoView({behavior:'smooth',block:'start'})})}
  document.querySelectorAll('section.place h2').forEach(h=>h.addEventListener('click',()=>{const p=S.places.find(q=>q.folder===h.parentNode.id);select(p,true)}));
  const b=bounds([S]);if(b.length)m.fitBounds(b,{padding:[30,30]});
  const id=decodeURIComponent(location.hash.slice(1));const p0=S.places.find(q=>q.folder===id);if(p0)setTimeout(()=>{select(p0,true);document.getElementById(id).scrollIntoView()},50);
}

/* all sessions: list, filters, one map */
function initHub(D){
  const m=makeMap('map'),g=L.layerGroup().addTo(m),selg=L.layerGroup().addTo(m),$=id=>document.getElementById(id);
  const on=new Set(D.map(s=>s.id+s.rel));
  const key=s=>s.id+s.rel;
  function sesOk(s){const k=$('fkind').value,h=$('fhold').value,a=$('ffrom').value,b=$('fto').value;
    return(!k||s.kind===k)&&(!h||s.holding===h)&&(!a||s.date>=a)&&(!b||s.date<=b)&&(!$('fdoors').checked||s.doors>0)}
  function placeOk(p){const q=$('fq').value.trim().toLowerCase(),c=$('fconf').value,gs=$('fg').value;
    const txt=(p.name+' '+(p.category||'')+' '+((p.google||{}).name||'')).toLowerCase();
    return(!q||txt.includes(q))&&(!c||p.confidence===c)&&(!gs||gstat(p)===gs)}
  function render(fit){
    g.clearLayers();selg.clearLayers();const vis=[];let n=0,nw=0,errs=[];const list=$('slist');list.innerHTML='';
    for(const s of D){if(!sesOk(s))continue;const ps=s.places.filter(placeOk),chk=on.has(key(s));
      const st={found:0,new:0,todo:0};for(const p of ps)st[gstat(p)]++;
      const li=document.createElement('label');li.className='ses'+(chk?' on':'');
      li.innerHTML='<input type="checkbox" '+(chk?'checked':'')+'><div><b>'+esc(s.name)+'</b><br><span class="muted">'+esc(s.date)+' · '+
        esc(s.kind==='poi'?'place capture':s.holding==='hand'?'sweep, in hand':'sweep, mounted')+'</span><br>'+ps.length+' places'+
        (st.new?' <span class="chip new">'+st.new+' new</span>':'')+(st.todo?' <span class="chip todo">'+st.todo+' to look up</span>':'')+
        (s.medianErrorM!=null?'<br><span class="muted">vs '+s.doors+' doors: median '+s.medianErrorM+' m</span>':'')+
        ' · <a href="'+enc(s.rel)+'index.html">report</a></div>';
      li.querySelector('input').onchange=e=>{e.target.checked?on.add(key(s)):on.delete(key(s));render(false)};list.appendChild(li);
      if(!chk)continue;const sv={...s,places:ps};vis.push(sv);drawRide(m,s,g);
      for(const p of ps){n++;if(gstat(p)==='new')nw++;if(p.truth&&p.truth.errorM!=null)errs.push(p.truth.errorM);drawTruth(p,g);
        L.marker([p.lat,p.lon],{icon:pinIcon(p,false),zIndexOffset:500}).addTo(g).bindTooltip(esc(p.n)+' · '+esc(p.name))
          .bindPopup(popup(p,s.rel,enc(s.rel)+'index.html#'+encodeURIComponent(p.folder)),{maxWidth:280})
          .on('click',()=>{selg.clearLayers();drawPlace(p,selg,true)})}}
    errs.sort((a,b)=>a-b);
    $('sum').innerHTML='<b>'+vis.length+'</b> sessions · <b>'+n+'</b> places · <b>'+nw+'</b> not on Google nearby'+
      (errs.length?' · vs doors: median <b>'+errs[errs.length>>1]+' m</b> ('+errs.length+')':'');
    if(fit){const b=bounds(vis);if(b.length)m.fitBounds(b,{padding:[30,30]})}}
  for(const id of ['fq','fkind','fhold','ffrom','fto','fconf','fg','fdoors'])$(id).addEventListener('input',()=>render(false));
  $('all').onclick=()=>{D.filter(sesOk).forEach(s=>on.add(key(s)));render(true)};
  $('none').onclick=()=>{on.clear();render(false)};$('fit').onclick=()=>render(true);
  m.on('popupclose',()=>selg.clearLayers());render(true);
}
"""


def session_map_block(folder):
    """The <head> extras and the map <script> for a sweep's own index.html."""
    data = session_data(folder)
    return (LEAFLET, f"<script>{JS}\ninitSession({script_json(data)});</script>")


HUB_CSS = """
body{display:grid;grid-template-columns:340px 1fr;height:100vh;overflow:hidden}
#side{overflow:auto;padding:16px;border-right:1px solid var(--line);background:var(--card)}
#main{display:grid;grid-template-rows:auto 1fr;min-width:0}#sum{padding:10px 14px;border-bottom:1px solid var(--line);background:var(--card)}
#map{min-height:0}.f{display:grid;grid-template-columns:1fr 1fr;gap:6px;margin:12px 0}
.f input,.f select{width:100%;padding:6px 8px;border:1px solid var(--line);border-radius:8px;font:inherit;background:#fff;color:var(--ink)}
.f .wide{grid-column:1/-1}.f input[type=checkbox]{width:auto;margin:0 6px 0 0;vertical-align:-2px}.f label{font-size:12px;color:var(--muted)}
.ses{display:flex;gap:8px;padding:9px 10px;border:1px solid var(--line);border-radius:10px;margin:6px 0;cursor:pointer;background:#fff}
.ses.on{border-color:var(--accent);box-shadow:inset 3px 0 0 var(--accent)}.ses input{margin-top:3px}
.btns button{font:inherit;padding:4px 10px;border:1px solid var(--line);border-radius:8px;background:#fff;cursor:pointer;margin-right:4px}
@media(max-width:760px){body{grid-template-columns:1fr;grid-template-rows:auto 60vh;height:auto;overflow:auto}#side{border-right:0}#main{grid-row:1}}
"""


def write_hub(root):
    sessions = []
    for d in sorted(os.listdir(root)):
        f = os.path.join(root, d)
        if os.path.isdir(f) and not d.startswith('.'):
            s = session_data(f, d + '/')
            if s:
                sessions.append(s)
    if not sessions:
        return None
    sessions.sort(key=lambda s: s.get('started') or '', reverse=True)
    e = html.escape
    page = f"""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sweep sessions</title>{LEAFLET}<style>{CSS}{HUB_CSS}</style>
<div id="side"><h1>Sweep sessions</h1><div class="muted">{len(sessions)} processed sessions in {e(os.path.abspath(root))}<br>
updated {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M')} UTC</div>
<div class="f"><div class="wide"><label>Search places</label><input id="fq" placeholder="name, category, Google name"></div>
<div><label>Kind</label><select id="fkind"><option value="">all</option><option value="sweep">street sweep</option><option value="poi">place capture</option></select></div>
<div><label>Phone</label><select id="fhold"><option value="">all</option><option value="mount">mounted</option><option value="hand">in hand</option></select></div>
<div><label>From</label><input type="date" id="ffrom"></div><div><label>To</label><input type="date" id="fto"></div>
<div><label>Sign read</label><select id="fconf"><option value="">all</option><option value="high">fully</option><option value="low">partly (low)</option></select></div>
<div><label>Google Maps</label><select id="fg"><option value="">all</option><option value="found">found</option><option value="new">not found nearby</option><option value="todo">not looked up</option></select></div>
<label class="wide"><input type="checkbox" id="fdoors"> only sessions with doors captured on site</label></div>
<div class="btns"><button id="all">Show all</button><button id="none">Hide all</button><button id="fit">Fit map</button></div>
<div id="slist"></div></div>
<div id="main"><div id="sum"></div><div id="map"></div></div>
<script>{JS}\ninitHub({script_json(sessions)});</script></html>"""
    out = os.path.join(root, 'index.html')
    open(out, 'w', encoding='utf-8').write(page)
    return out


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    print(write_hub(sys.argv[1]) or 'no processed sessions found')
