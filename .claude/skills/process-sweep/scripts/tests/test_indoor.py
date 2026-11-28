"""Indoor walks (docs/indoor.md): positions recomputed from indoor.jsonl, the footprint, levels, directory boards.

    python -m unittest discover -s .claude/skills/process-sweep/scripts/tests -v
"""
import contextlib, io, json, math, os, shutil, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import build  # noqa: E402
import indoor  # noqa: E402
import prepare  # noqa: E402

LAT0, LON0 = 10.80, 106.74
W, H = 60.0, 100.0                       # the building: a 60 m x 100 m rectangle from (0, 0)
REAL_STEP, PHONE_STEP = 0.75, 0.70       # the phone under-counts the step length by 7 %
SEC = 10 ** 9


def ll(x, y):
    return list(indoor.to_ll(x, y, LAT0, LON0))


def building(**extra):
    ring = [ll(0, 0), ll(W, 0), ll(W, H), ll(0, H)]
    return {'osm': 'way/1', 'name': 'Test Mall', 'levels': 3, 'footprint': ring, 'outer': [ring], 'holes': [],
            'addr': {'housenumber': '161', 'street': 'Xa Lộ Hà Nội', 'city': 'Thủ Đức'}, **extra}


def walk():
    """A rectangle walked with drift (heading off by 4 deg growing to ~8, steps 0.75 m counted as 0.70), then on
    after the last anchor: north 100 steps and 30 steps west, out through the wall.
    -> (log lines, true positions [(t, x, y)], anchor times)."""
    legs = [(90, 66), (0, 40), (270, 66), (180, 40), (0, 100), (270, 30)]
    x, y, t, k = 5.0, 5.0, 0, 0
    lines = [{'t': 0, 'type': 'level', 'level': '1', 'how': 'picked'},
             {'t': SEC // 2, 'type': 'anchor', 'lat': ll(x, y)[0], 'lon': ll(x, y)[1], 'level': '1', 'how': 'tap'}]
    truth = [(SEC // 2, x, y)]
    anchors_after = {106: (54.5, 35.0), 212: (5.0, 5.0)}
    for heading, n in legs:
        for _ in range(n):
            k += 1
            t = k * SEC
            bias = 4 + 0.02 * k
            lines.append({'t': t - SEC // 10, 'type': 'yaw', 'deg': (heading + bias) % 360})
            lines.append({'t': t, 'type': 'step'})
            x += REAL_STEP * math.sin(math.radians(heading))
            y += REAL_STEP * math.cos(math.radians(heading))
            truth.append((t, x, y))
            if k in anchors_after:
                ax, ay = anchors_after[k]
                lines.append({'t': t + SEC // 2, 'type': 'anchor', 'lat': ll(ax, ay)[0], 'lon': ll(ax, ay)[1],
                              'level': '1', 'how': 'tap'})
            if k == 260:
                lines.append({'t': t + SEC // 3, 'type': 'level', 'level': '2', 'how': 'confirmed'})
    return [json.dumps(o) for o in lines], truth


def err(fix, x, y):
    return math.hypot(*indoor.to_xy(fix['lat'], fix['lon'], *ll(x, y)))


class Recompute(unittest.TestCase):
    def setUp(self):
        lines, self.truth = walk()
        self.ev = indoor.parse(lines)
        self.session = {'kind': 'indoor', 'building': building(), 'indoor': {'stepLengthM': PHONE_STEP}}
        self.times = [t for t, _x, _y in self.truth]

    def test_anchored_walk_lands_on_the_truth(self):
        fixes, track, summary = indoor.recompute(self.ev, self.times, self.session)
        self.assertEqual(summary['anchors'], 3)
        self.assertEqual(sum(summary['stretches'].values()), 2)
        self.assertTrue(all('rotated' in h for h in summary['stretches']))
        between = [err(f, x, y) for f, (t, x, y) in zip(fixes, self.truth) if t <= 212 * SEC]
        self.assertLess(max(between), 1.5)
        # the raw walk, only moved onto the first anchor, is several metres off by the corner
        raw = indoor.dead_reckon(self.ev, PHONE_STEP)
        rx, ry = raw[106][1] + 5, raw[106][2] + 5
        self.assertGreater(math.hypot(rx - 54.5, ry - 35), 4)
        self.assertTrue(track and all(len(p) == 3 for p in track))

    def test_uncertainty_grows_after_the_last_anchor(self):
        fixes, _t, _s = indoor.recompute(self.ev, self.times, self.session)
        self.assertEqual(fixes[0]['uncertaintyM'], 3.0)
        self.assertEqual(fixes[212]['uncertaintyM'], 3.0)
        end = fixes[-1]
        self.assertAlmostEqual(end['sinceAnchorM'], 130 * PHONE_STEP, delta=0.5)
        self.assertAlmostEqual(end['uncertaintyM'], 0.05 * 130 * PHONE_STEP, delta=0.1)
        self.assertIn('after the last anchor', end['how'])

    def test_kept_inside_the_footprint(self):
        fixes, track, _s = indoor.recompute(self.ev, self.times, self.session)
        fp = indoor.Footprint(self.session['building'])
        end = fixes[-1]                                   # walked 30 steps west through the wall at x = 0
        self.assertGreater(end['clampedM'], 0)
        self.assertTrue(fp.inside(end['lat'], end['lon']))
        x, _y = indoor.to_xy(end['lat'], end['lon'], LAT0, LON0)
        self.assertAlmostEqual(x, indoor.WALL_IN_M, delta=0.05)   # slid along the wall, just inside
        self.assertTrue(all(fp.inside(p[0], p[1]) for p in track))

    def test_levels(self):
        fixes, track, _s = indoor.recompute(self.ev, self.times, self.session)
        self.assertEqual(fixes[100]['level'], '1')
        self.assertEqual(fixes[261]['level'], '2')
        self.assertEqual({p[2] for p in track}, {'1', '2'})
        self.assertEqual(indoor.level_at(self.ev, -5, fallback='G'), 'G')   # before any event: the photo's own

    def test_one_anchor_translates_only(self):
        ev = dict(self.ev, anchors=self.ev['anchors'][:1])
        fixes, _t, summary = indoor.recompute(ev, self.times[:50], self.session)
        self.assertEqual(summary['stretches'], {})
        self.assertIn('only anchor', fixes[10]['how'])
        self.assertLess(err(fixes[0], 5, 5), 0.01)

    def test_no_anchor_no_fix(self):
        fixes, track, summary = indoor.recompute(dict(self.ev, anchors=[]), self.times[:3], self.session)
        self.assertEqual(fixes, [None, None, None])
        self.assertEqual(track, [])
        self.assertIn('no anchor', summary['why'])

    def test_courtyard_hole(self):
        b = building(holes=[[ll(20, 20), ll(40, 20), ll(40, 40), ll(20, 40)]])
        fp = indoor.Footprint(b)
        la, lo, moved = fp.clamp(*ll(22, 30))              # in the courtyard: out to its nearest wall
        x, y = indoor.to_xy(la, lo, LAT0, LON0)
        self.assertTrue(fp.inside(la, lo))
        self.assertAlmostEqual(x, 20 - indoor.WALL_IN_M, delta=0.05)
        self.assertAlmostEqual(moved, 2 + indoor.WALL_IN_M, delta=0.05)


class LevelsAndUnits(unittest.TestCase):
    def test_units(self):
        self.assertEqual(indoor.norm_unit('l2 - 015'), 'L2-15')
        self.assertEqual(indoor.unit_tail('L2-15A'), '15A')
        self.assertEqual(indoor.unit_level('L2-15'), '2')
        self.assertEqual(indoor.unit_level('B1-03'), 'B1')
        self.assertIsNone(indoor.unit_level('15'))
        self.assertEqual(indoor.unit_hints('Highlands L2-15 giá 45.000 B1.03'), ['L2-15', 'B1-3'])
        self.assertIsNone(indoor.unit_hints('45.000đ 0901 234 567'))

    def test_levels(self):
        self.assertEqual([indoor.norm_level(x) for x in ('Tầng 2', 'L3', 'Hầm 1', 'Trệt', 'GF', '4')],
                         ['2', '3', 'B1', 'G', 'G', '4'])
        self.assertEqual(sorted(['2', 'G', 'B1', '1', 'B2', 'Rooftop'], key=indoor.level_key),
                         ['B2', 'B1', 'G', '1', '2', 'Rooftop'])

    def test_building_address(self):
        b = indoor.building_block(building(addr=None, tags={'addr:housenumber': '161', 'addr:street': 'Xa Lộ Hà Nội'}))
        self.assertEqual(b['addr'], {'housenumber': '161', 'street': 'Xa Lộ Hà Nội'})
        self.assertEqual(b['address'], '161 Xa Lộ Hà Nội')


class Directory(unittest.TestCase):
    def test_match(self):
        places = [{'name': 'Highlands Coffee', 'level': '2', 'unit': 'L2-15'},
                  {'name': 'Uniqlo', 'level': '1', 'unit': None},
                  {'name': 'Pharmacity', 'level': '2', 'unit': 'L2-20'},
                  {'name': 'Phúc Long', 'level': 'Tầng 3', 'unit': '07'}]
        d = indoor.match_directory([
            {'level': '2', 'unit': 'L2-015', 'name': 'HIGHLANDS', 'photo': 3},
            {'level': '1', 'unit': 'L1-02', 'name': 'UNIQLO', 'photo': 3},
            {'level': '2', 'unit': 'L2-30', 'name': 'Adidas', 'photo': 3},
            {'level': '3', 'unit': 'L3-20', 'name': 'Pharmacity', 'photo': 4},     # another level: not that one
            {'level': 'L3', 'unit': 'L3-07', 'name': 'Phuc Long Coffee & Tea', 'photo': 4},
        ], places)
        self.assertEqual([(e['match'], e['by']) for e in d],
                         [(0, 'unit'), (1, 'name'), (None, None), (None, None), (3, 'unit')])


def write(path, data):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(data if isinstance(data, str) else json.dumps(data, ensure_ascii=False))


def read(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


class Prepare(unittest.TestCase):
    """prepare.py on a raw indoor session folder: corrected positions, the live ones kept, levels, unit hints."""

    def setUp(self):
        from PIL import Image
        self.d = tempfile.mkdtemp()
        self.src = os.path.join(self.d, 'session')
        os.makedirs(os.path.join(self.src, 'photos'))
        lines, truth = walk()
        write(os.path.join(self.src, 'indoor.jsonl'), '\n'.join(lines) + '\n')
        write(os.path.join(self.src, 'session.json'), {'id': 's_in', 'name': 'Mall', 'kind': 'indoor', 'target': 'shops',
                                                       'building': building(), 'indoor': {'stepLengthM': PHONE_STEP}})
        self.truth = truth
        items = []
        for n, k in enumerate((10, 60, 150, 270)):
            t, x, y = truth[k]
            pid = f'p{n}'
            Image.new('RGB', (48, 64), (n * 50, 80, 120)).save(os.path.join(self.src, 'photos', pid + '.jpg'))
            items.append({'id': pid, 'kind': 'photo', 't': f'2026-11-25T10:00:0{n}Z', 'elapsedNanos': t,
                          'lat': ll(x + 6, y)[0], 'lon': ll(x + 6, y)[1], 'level': '1', 'positionSource': 'pdr',
                          'sinceAnchorM': 20, 'cameraBearing': 90, 'cameraBearingSource': 'pdr-yaw', 'hFovDeg': 60,
                          'width': 48, 'height': 64, 'ocr': {'text': 'GUCCI L2-15' if n == 3 else ''},
                          'trigger': 'directory' if n == 1 else None})
        write(os.path.join(self.src, 'items.jsonl'), '\n'.join(json.dumps(i) for i in items) + '\n')
        self.work = os.path.join(self.d, 'work')

    def tearDown(self):
        shutil.rmtree(self.d)

    def run_prepare(self):
        old = sys.argv
        sys.argv = ['prepare.py', self.src, self.work]
        try:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                prepare.main()
        finally:
            sys.argv = old
        return read(os.path.join(self.work, 'photos.json')), out.getvalue()

    def test_positions_recomputed(self):
        photos, log = self.run_prepare()
        self.assertIn('recomputed', log)
        for p, k in zip(photos, (10, 60, 150, 270)):
            _t, x, y = self.truth[k]
            self.assertIsNotNone(p['livePosition'])
            self.assertGreater(math.hypot(*indoor.to_xy(p['livePosition']['lat'], p['livePosition']['lon'], *ll(x, y))), 5)
            if k <= 212:
                self.assertLess(math.hypot(*indoor.to_xy(p['lat'], p['lon'], *ll(x, y))), 1.5)
            self.assertTrue(p['positionFrom'].startswith('indoor walk'))
            self.assertGreaterEqual(p['uncertaintyM'], 3)
            self.assertIsNone(p['roadName'])
        self.assertEqual([p['level'] for p in photos], ['1', '1', '1', '2'])
        self.assertEqual(photos[3]['unitHints'], ['L2-15'])
        self.assertEqual(prepare.sheet_label(photos[1]), ['#1  lvl 1', 'DIRECTORY'])
        self.assertEqual(prepare.sheet_label(photos[3]), ['#3  lvl 2', 'L2-15?'])
        tr = read(os.path.join(self.work, 'indoor_track.json'))
        self.assertTrue(tr['log'] and len(tr['track']) > 50)

    def test_without_the_log_the_live_positions_stay(self):
        os.remove(os.path.join(self.src, 'indoor.jsonl'))
        photos, log = self.run_prepare()
        self.assertIn('no indoor.jsonl', log)
        self.assertEqual((photos[0]['lat'], photos[0]['lon']), (photos[0]['livePosition']['lat'], photos[0]['livePosition']['lon']))
        self.assertEqual(photos[0]['level'], '1')
        self.assertEqual(photos[0]['uncertaintyM'], 3.0)   # max(3, 5 % of 20 m)
        self.assertEqual(len(read(os.path.join(self.work, 'indoor_track.json'))['track']), 4)


class Build(unittest.TestCase):
    """build.py on an indoor walk: level / unit / building on every place, kept inside, directory matched, the
    unmatched entry a visit, and the exports carry level and unit."""

    def setUp(self):
        from PIL import Image
        self.d = tempfile.mkdtemp()
        work, self.out = os.path.join(self.d, 'work'), os.path.join(self.d, 'out', 'mall')
        os.makedirs(work)
        photos = []
        # walking north along x = 3 m, a metre inside the west wall; shops on both sides
        for i in range(8):
            f = os.path.join(work, f'p{i}.jpg')
            Image.new('RGB', (48, 64), (i * 30, 90, 120)).save(f)
            la, lo = ll(3, 10 + 4 * i)
            photos.append({'idx': i, 'id': f'p{i}', 'file': f, 't': f'2026-11-25T10:00:0{i}Z', 'lat': la, 'lon': lo,
                           'accuracyM': 3, 'uncertaintyM': 3, 'cameraBearing': 90, 'cameraBearingSource': 'pdr-yaw',
                           'hFovDeg': 60, 'width': 48, 'height': 64, 'speedMps': 1, 'roadName': None, 'blurry': False,
                           'sharpness': 10, 'level': '2' if i < 6 else '3', 'trigger': 'directory' if i == 7 else None})
        write(os.path.join(work, 'photos.json'), photos)
        write(os.path.join(work, 'session.json'), {'id': 's_in', 'name': 'Mall', 'kind': 'indoor', 'target': 'shops',
                                                   'building': building()})
        write(os.path.join(work, 'indoor_track.json'), {'track': [[*ll(3, 10 + i), '2' if i < 22 else '3'] for i in range(30)]})
        write(os.path.join(work, 'places.json'), {
            'street': 'ignored indoors',
            'places': [
                {'name': 'Highlands Coffee', 'unit': 'L2-15', 'photos': [0, 1], 'cover': 1,
                 'sightings': [{'photo': 1, 'x': 0.5}], 'confidence': 'high'},
                # looked at the west wall: the single-photo estimate lands outside, it is moved back in
                {'name': 'Uniqlo', 'level': 'Tầng 2', 'photos': [2, 3], 'cover': 3,
                 'sightings': [{'photo': 3, 'x': 0.5}], 'confidence': 'high', 'agentPin': {'snap': 'facade'}},
                {'name': 'Gucci', 'unit': 'L2-40', 'photos': [6, 6], 'cover': 6, 'sightings': [{'photo': 6, 'x': 0.5}],
                 'confidence': 'high'},
            ],
            'directory': [
                {'level': '2', 'unit': 'L2-015', 'name': 'Highlands', 'photo': 7},
                {'level': '2', 'unit': 'L2-21', 'name': 'UNIQLO', 'photo': 7},
                {'level': '2', 'unit': 'L2-16', 'name': 'Adidas', 'photo': 7},
            ]})
        # Uniqlo looks west, through the wall 3 m away
        ph = read(os.path.join(work, 'photos.json'))
        ph[3]['cameraBearing'] = 270
        write(os.path.join(work, 'photos.json'), ph)
        self.work = work

    def tearDown(self):
        shutil.rmtree(self.d)

    def run_build(self):
        with contextlib.redirect_stdout(io.StringIO()):
            build.build(self.work, self.out)
        return read(os.path.join(self.out, 'result.json'))

    def test_places(self):
        res = self.run_build()
        by = {p['name']: p for p in res['places']}
        h, u, g = by['Highlands Coffee'], by['Uniqlo'], by['Gucci']
        self.assertTrue(h['indoor'])
        self.assertEqual((h['level'], h['levelFrom'], h['unit']), ('2', 'walk', 'L2-15'))
        self.assertEqual(h['fields']['level'], {'value': '2', 'photo': 1, 'sure': 'medium', 'source': 'walk'})
        self.assertEqual(h['fields']['unit']['value'], 'L2-15')
        self.assertEqual((u['level'], u['levelFrom']), ('2', 'read'))
        self.assertEqual(g['level'], '3')                          # the walk's level at its cover photo
        self.assertIn('names level 2', g['note'])                  # L2-40 read on level 3
        self.assertEqual(h['building']['name'], 'Test Mall')
        self.assertEqual(h['building']['address'], '161 Xa Lộ Hà Nội, Thủ Đức')
        for p in res['places']:
            self.assertNotIn('street', p['fields'])                # no ride road indoors
        fp = indoor.Footprint(building())
        for p in res['places']:
            self.assertTrue(fp.inside(p['pins']['algorithm']['lat'], p['pins']['algorithm']['lon']))
        self.assertIn('back inside the building', u['pins']['algorithm']['method'])
        self.assertIsNone(u['pins']['agent'])                      # no facade snap indoors
        self.assertEqual(res['session']['kind'], 'indoor')
        self.assertIsNone(res['session']['street'])

    def test_directory(self):
        res = self.run_build()
        by = {p['name']: p for p in res['places']}
        self.assertEqual(by['Highlands Coffee']['directory']['by'], 'unit')
        self.assertIn('on the directory board', by['Highlands Coffee']['note'])
        self.assertEqual(by['Uniqlo']['directory']['by'], 'name')
        self.assertEqual(by['Uniqlo']['fields']['unit']['value'], 'L2-21')   # the board's unit, it had none
        self.assertIsNone(by['Gucci'].get('directory'))
        visits = [t for t in res['tasks'] if t['type'] == 'visit' and t['place'] is None]
        self.assertEqual(len(visits), 1)
        v = visits[0]
        self.assertEqual((v['name'], v['level'], v['unit']), ('Adidas', '2', 'L2-16'))
        self.assertIn('directory board', v['why'])
        self.assertTrue(v['photo'].startswith('_directory/'))
        self.assertTrue(os.path.isfile(os.path.join(self.out, v['photo'])))
        # next to L2-15 (Highlands), the nearest unit number on level 2
        hp = by['Highlands Coffee']['pins']['algorithm']
        self.assertEqual((v['lat'], v['lon']), (round(hp['lat'], 6), round(hp['lon'], 6)))
        self.assertEqual(len(res['directory']), 3)
        self.assertEqual(res['directory'][0]['place'], by['Highlands Coffee']['folder'])

    def test_walk_levels_and_building(self):
        res = self.run_build()
        self.assertEqual(res['building']['name'], 'Test Mall')
        self.assertTrue(res['building']['footprint'])
        self.assertEqual(res['levels'], ['2', '3'])
        self.assertEqual(len(res['track']), 30)
        self.assertEqual(len(res['trackLevels']), 30)
        self.assertEqual(res['shots'][0][4], '2')

    def test_exports(self):
        self.run_build()
        g = read(os.path.join(self.out, 'places.geojson'))
        props = {f['properties']['name']: f['properties'] for f in g['features']}
        self.assertEqual((props['Highlands Coffee']['level'], props['Highlands Coffee']['unit']), ('2', 'L2-15'))
        self.assertEqual(props['Highlands Coffee']['building'], 'Test Mall')
        with open(os.path.join(self.out, 'places.csv'), encoding='utf-8-sig') as f:
            rows = f.read().splitlines()
        self.assertTrue(rows[0].endswith(',level,unit,building'))
        self.assertTrue(any('Highlands Coffee' in r and r.endswith(',2,L2-15,Test Mall') for r in rows))
        pj = read(os.path.join(self.out, '01_Highlands_Coffee', 'place.json'))['place']
        self.assertEqual(pj['address'], '161 Xa Lộ Hà Nội, Thủ Đức')
        self.assertIsNone(pj['street'])


if __name__ == '__main__':
    unittest.main()
