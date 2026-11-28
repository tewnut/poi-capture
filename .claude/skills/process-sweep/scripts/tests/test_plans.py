"""Floor plans on an indoor walk (docs/indoor.md §7): toMap, plan sheets, plan anchors, units snapped and visited.

    python -m unittest discover -s .claude/skills/process-sweep/scripts/tests -v
"""
import contextlib, io, json, math, os, shutil, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import build  # noqa: E402
import indoor  # noqa: E402
import prepare  # noqa: E402
from test_indoor import LAT0, LON0, PHONE_STEP, building, ll, read, walk, write  # noqa: E402

# the same numbers as web/src/indoor.test.ts ("toMap")
TM = {'origin': [10.80213, 106.74122], 'scaleMPerPx': 0.061, 'rotationDeg': 33.5}


class ToMap(unittest.TestCase):
    def test_reference_numbers(self):
        e, n = indoor.plan_xy(812, 640, TM)
        self.assertAlmostEqual(e, 19.7564126, places=6)
        self.assertAlmostEqual(n, -59.8934452, places=6)
        la, lo = indoor.plan_to_ll(812, 640, TM)
        self.assertAlmostEqual(la, 10.801588174, places=9)
        self.assertAlmostEqual(lo, 106.741400676, places=9)
        la, lo = indoor.plan_to_ll(2048, 1365, TM)
        self.assertAlmostEqual(la, 10.800878092, places=9)
        self.assertAlmostEqual(lo, 106.741752419, places=9)
        self.assertEqual(indoor.plan_to_ll(0, 0, TM), (10.80213, 106.74122))

    def test_round_trip(self):
        for px, py in ((0, 0), (812, 640), (2048, 1365), (-30.5, 99.25)):
            x, y = indoor.ll_to_plan(*indoor.plan_to_ll(px, py, TM), TM)
            self.assertAlmostEqual(x, px, places=6)
            self.assertAlmostEqual(y, py, places=6)

    def test_rotation_is_clockwise_and_y_flipped(self):
        tm = {'origin': [10.8, 106.7], 'scaleMPerPx': 0.5, 'rotationDeg': 0}
        self.assertEqual(indoor.plan_xy(10, 0, tm), (5.0, -0.0))            # x right = east
        self.assertEqual(indoor.plan_xy(0, 10, tm), (0.0, -5.0))            # y down = south
        e, n = indoor.plan_xy(10, 0, dict(tm, rotationDeg=90))              # clockwise: east turns south
        self.assertAlmostEqual(e, 0, places=9)
        self.assertAlmostEqual(n, -5, places=9)

    def test_unit_geometry(self):
        tm = {'origin': list(ll(0, 100)), 'scaleMPerPx': 0.5, 'rotationDeg': 0}
        g = indoor.plan_unit_geo({'polygon': [[20, 160], [40, 160], [40, 180], [20, 180]]}, {'toMap': tm})
        self.assertLess(math.hypot(*indoor.to_xy(*g['centre'], *ll(15, 15))), 0.01)
        self.assertAlmostEqual(g['radiusM'], math.hypot(5, 5), places=3)
        self.assertAlmostEqual(g['areaM2'], 100, places=3)
        self.assertEqual(len(g['polygon']), 4)

    def test_plans_of(self):
        ok = {'level': 'Tầng 2', 'file': 'plans/plan_2_1.jpg', 'size': [100, 50], 'toMap': TM}
        bad = {'level': '3', 'file': 'plans/plan_3_1.jpg', 'toMap': {'origin': [1, 2]}}
        self.assertEqual([p['level'] for p in indoor.plans_of({'building': {'plans': [ok, bad]}})], ['2'])
        self.assertEqual(len(indoor.plans_of({'building': {}}, {'plans': [ok]})), 1)
        self.assertEqual(len(indoor.plans_of({'building': None}, [ok])), 1)

    def test_plan_anchor_placed_again(self):
        ev = indoor.parse([json.dumps(o) for o in (
            {'t': 5, 'type': 'plan', 'level': '2', 'file': 'plans/plan_2_1.jpg'},
            {'t': 10, 'type': 'anchor', 'lat': 10.8, 'lon': 106.74, 'level': '2', 'how': 'plan'},
            {'t': 20, 'type': 'anchor', 'lat': 10.8, 'lon': 106.74, 'level': '2', 'how': 'tap'})])
        self.assertEqual(len(ev['anchors']), 2)                             # a plan anchor is an anchor
        plans = [{'level': '2', 'file': 'plans/plan_2_1.jpg', 'toMap': TM, 'youAreHere': [812, 640], 't': 9}]
        self.assertEqual(indoor.plan_anchors(ev, plans), 1)
        a = ev['anchors'][0]
        self.assertEqual((round(a['lat'], 9), round(a['lon'], 9)), (10.801588174, 106.741400676))
        self.assertEqual((ev['anchors'][1]['lat'], ev['anchors'][1]['lon']), (10.8, 106.74))   # a tap stays


    def test_unit_anchor_and_diagnostic_lines(self):
        ev = indoor.parse([json.dumps(o) for o in (
            {'t': 1, 'type': 'anchor', 'lat': 10.8, 'lon': 106.74, 'level': '2', 'how': 'unit'},
            {'t': 2, 'type': 'lookup', 'what': 'x'}, {'t': 3, 'type': 'pdr', 'q': 1}, {'t': 4, 'type': 'pdr'},
            {'t': 5, 'type': 'floor-prompt'}, {'t': 6, 'type': 'plan-corners'}, {'t': 7, 'type': 'plan-place'})])
        self.assertEqual([a['how'] for a in ev['anchors']], ['unit'])
        self.assertEqual(ev['other'], {'lookup': 1, 'pdr': 2, 'floor-prompt': 1, 'plan-corners': 1, 'plan-place': 1})

    def test_phone_units(self):
        plan = {'units': [{'unit': 'L2-15', 'x': 812, 'y': 640, 'h': 18}, {'unit': '', 'x': 1, 'y': 2},
                          {'unit': 'L2-16', 'x': 'a', 'y': 2}, 'junk']}
        self.assertEqual(indoor.phone_units(plan), [{'unit': 'L2-15', 'x': 812, 'y': 640, 'h': 18}])


class PreparePlans(unittest.TestCase):
    def setUp(self):
        from PIL import Image
        self.d = tempfile.mkdtemp()
        self.src = os.path.join(self.d, 'session')
        os.makedirs(os.path.join(self.src, 'photos'))
        os.makedirs(os.path.join(self.src, 'plans'))
        lines, _truth = walk()
        write(os.path.join(self.src, 'indoor.jsonl'), '\n'.join(lines) + '\n')
        Image.new('RGB', (2048, 1365), (240, 240, 230)).save(os.path.join(self.src, 'plans', 'plan_1_1.jpg'))
        plan = {'level': '1', 'photo': 'p0', 'file': 'plans/plan_1_1.jpg', 'size': [2048, 1365],
                'toMap': {'origin': list(ll(0, 100)), 'scaleMPerPx': 0.03, 'rotationDeg': 0}, 'placedBy': 'footprint',
                'youAreHere': [812, 640], 't': 1, 'capture': 'mlkit-scanner', 'corners': None,
                'units': [{'unit': 'L1-07', 'x': 300, 'y': 400, 'h': 18}]}
        write(os.path.join(self.src, 'plans', 'plans.json'), [plan])
        write(os.path.join(self.src, 'session.json'), {'id': 's_pl', 'name': 'Mall', 'kind': 'indoor', 'target': 'shops',
                                                       'building': building(), 'indoor': {'stepLengthM': PHONE_STEP}})
        Image.new('RGB', (48, 64), (9, 80, 120)).save(os.path.join(self.src, 'photos', 'p0.jpg'))
        write(os.path.join(self.src, 'items.jsonl'), json.dumps(
            {'id': 'p0', 'kind': 'photo', 't': '2026-11-25T10:00:00Z', 'elapsedNanos': 10 ** 9, 'lat': LAT0,
             'lon': LON0, 'level': '1', 'cameraBearing': 90, 'width': 48, 'height': 64, 'trigger': 'plan',
             'nearUnit': {'unit': 'L1-07', 'distanceM': 3.2}}) + '\n')
        self.work = os.path.join(self.d, 'work')

    def tearDown(self):
        shutil.rmtree(self.d)

    def test_sheets_and_printout(self):
        old = sys.argv
        sys.argv = ['prepare.py', self.src, self.work]
        try:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                prepare.main()
        finally:
            sys.argv = old
        log = out.getvalue()
        self.assertIn('floor plans: 1', log)
        self.assertIn('plans/plan_1_1.jpg', log)
        plans = read(os.path.join(self.work, 'plans.json'))
        self.assertEqual(len(plans), 1)
        self.assertTrue(os.path.isfile(plans[0]['path']))
        sheets = plans[0]['sheets']
        self.assertTrue(sheets[0].endswith('plan_1_1.jpg'))
        self.assertGreater(len(sheets), 4)                                 # the whole plan + enlarged tiles
        for f in sheets:
            self.assertTrue(os.path.isfile(f))
        photos = read(os.path.join(self.work, 'photos.json'))
        self.assertEqual(prepare.sheet_label(photos[0]), ['#0  lvl 1', 'FLOOR PLAN', 'near L1-07 3m?'])
        self.assertIn('the phone read 1 unit label(s)', log)
        self.assertIn('L1-07 (300, 400)', log)
        self.assertIn('indoor diagnostics: anchors', log)
        self.assertIn('drift corrected at each anchor', log)

    def test_a_plan_path_cannot_leave_the_session(self):
        p = read(os.path.join(self.src, 'plans', 'plans.json'))
        p[0]['file'] = '../../outside.jpg'
        write(os.path.join(self.src, 'plans', 'plans.json'), p)
        plans = prepare.find_plans(self.src, os.path.join(self.d, 'work'), {'kind': 'indoor', 'building': {}})
        self.assertIsNone(plans[0]['path'])


class BuildPlans(unittest.TestCase):
    """build.py with a floor plan of level 2: the plan is the 60 m x 100 m building at 0.5 m/px (120 x 200 px), north
    up, its top-left corner the building's north-west corner."""

    def setUp(self):
        from PIL import Image
        self.d = tempfile.mkdtemp()
        work, self.out = os.path.join(self.d, 'work'), os.path.join(self.d, 'out', 'mall')
        os.makedirs(work)
        photos = []
        for i in range(8):
            f = os.path.join(work, f'p{i}.jpg')
            Image.new('RGB', (48, 64), (i * 30, 90, 120)).save(f)
            la, lo = ll(3, 10 + 4 * i)
            photos.append({'idx': i, 'id': f'p{i}', 'file': f, 't': f'2026-11-25T10:00:0{i}Z', 'lat': la, 'lon': lo,
                           'accuracyM': 3, 'uncertaintyM': 3, 'cameraBearing': 90, 'cameraBearingSource': 'pdr-yaw',
                           'hFovDeg': 60, 'width': 48, 'height': 64, 'speedMps': 1, 'roadName': None, 'blurry': False,
                           'sharpness': 10, 'level': '2', 'trigger': 'directory' if i == 7 else None})
        write(os.path.join(work, 'photos.json'), photos)
        write(os.path.join(work, 'session.json'), {'id': 's_pl', 'name': 'Mall', 'kind': 'indoor', 'target': 'shops',
                                                   'building': building()})
        write(os.path.join(work, 'indoor_track.json'), {'track': [[*ll(3, 10 + i), '2'] for i in range(30)]})
        img = os.path.join(work, 'plan_2_1.jpg')
        Image.new('RGB', (120, 200), (240, 240, 230)).save(img)
        self.tm = {'origin': list(ll(0, 100)), 'scaleMPerPx': 0.5, 'rotationDeg': 0}
        write(os.path.join(work, 'plans.json'), [{'level': '2', 'file': 'plans/plan_2_1.jpg', 'size': [120, 200],
                                                  'toMap': self.tm, 'placedBy': 'footprint', 'path': img}])
        sq = lambda x0, y0: [[x0, y0], [x0 + 20, y0], [x0 + 20, y0 + 20], [x0, y0 + 20]]   # 10 m x 10 m
        self.spec = {
            'places': [
                {'name': 'Highlands Coffee', 'unit': 'L2-15', 'photos': [0, 1], 'cover': 1,
                 'sightings': [{'photo': 1, 'x': 0.5}], 'confidence': 'high'},
                {'name': 'Uniqlo', 'photos': [2, 3], 'cover': 3, 'sightings': [{'photo': 3, 'x': 0.5}],
                 'confidence': 'high'},
                {'name': 'Pharmacity', 'photos': [4, 5], 'cover': 5, 'sightings': [{'photo': 5, 'x': 0.5}],
                 'confidence': 'high'},
            ],
            'directory': [
                {'level': '2', 'unit': 'L2-21', 'name': 'UNIQLO', 'photo': 7},
                {'level': '2', 'unit': 'L2-16', 'name': 'Adidas', 'photo': 7},
            ],
            'planUnits': [
                {'level': '2', 'plan': 'plans/plan_2_1.jpg', 'unit': 'L2-15', 'polygon': sq(20, 160), 'name': None},
                # Uniqlo's unit comes from the directory board, its outline from the plan
                {'level': '2', 'plan': 'plans/plan_2_1.jpg', 'unit': 'L2-21', 'polygon': sq(20, 140), 'name': None},
                # matched by name (Pharmacity read without a unit)
                {'level': '2', 'plan': 'plans/plan_2_1.jpg', 'unit': 'L2-22', 'polygon': sq(20, 120),
                 'name': 'Pharmacity'},
                # on the directory board too, not seen: one visit, at the unit
                {'level': '2', 'plan': 'plans/plan_2_1.jpg', 'unit': 'L2-16', 'polygon': sq(60, 160), 'name': 'Adidas'},
                # only on the plan: a visit
                {'level': '2', 'plan': 'plans/plan_2_1.jpg', 'unit': 'L2-30', 'polygon': sq(60, 100), 'name': 'Zara'},
                # no name: geometry only
                {'level': '2', 'plan': 'plans/plan_2_1.jpg', 'unit': 'L2-31', 'polygon': sq(60, 60), 'name': None},
                # the same unit read again on a second board: kept once
                {'level': '2', 'plan': 'plans/plan_2_1.jpg', 'unit': 'L2-031', 'polygon': sq(60, 60), 'name': None},
            ]}
        write(os.path.join(work, 'places.json'), self.spec)
        self.work = work

    def tearDown(self):
        shutil.rmtree(self.d)

    def run_build(self):
        with contextlib.redirect_stdout(io.StringIO()):
            build.build(self.work, self.out)
        return read(os.path.join(self.out, 'result.json'))

    def test_snapped_to_the_unit(self):
        res = self.run_build()
        by = {p['name']: p for p in res['places']}
        h = by['Highlands Coffee']['pins']['algorithm']
        self.assertEqual(h['method'], 'floor plan unit')
        self.assertLess(math.hypot(*indoor.to_xy(h['lat'], h['lon'], *ll(15, 15))), 0.05)
        self.assertAlmostEqual(h['uncertaintyM'], 7.1, places=1)           # half the 10 m x 10 m unit's diagonal
        self.assertIn('walkPosition', h)
        self.assertGreater(h['walkPosition']['offsetM'], 1)
        self.assertEqual(h['planUnit']['unit'], 'L2-15')
        self.assertIn('on the floor plan', by['Highlands Coffee']['note'])
        u = by['Uniqlo']
        self.assertEqual(u['unit'], 'L2-21')
        self.assertLess(math.hypot(*indoor.to_xy(u['pins']['algorithm']['lat'], u['pins']['algorithm']['lon'], *ll(15, 25))), 0.05)
        ph = by['Pharmacity']
        self.assertEqual(ph['pins']['algorithm']['planUnit']['by'], 'name')
        self.assertEqual(ph['fields']['unit']['value'], 'L2-22')            # the plan's unit, it had none
        self.assertEqual(ph['fields']['unit']['note'], 'from the floor plan')

    def test_visits_and_geometry(self):
        res = self.run_build()
        visits = {t['name']: t for t in res['tasks'] if t['type'] == 'visit' and t['place'] is None}
        self.assertEqual(sorted(visits), ['Adidas', 'Zara'])
        z = visits['Zara']
        self.assertIn('floor plan', z['why'])
        self.assertEqual((z['level'], z['unit'], z['photo']), ('2', 'L2-30', '_plans/plan_2_1.jpg'))
        self.assertLess(math.hypot(*indoor.to_xy(z['lat'], z['lon'], *ll(35, 45))), 0.05)
        a = visits['Adidas']                                                # the directory's task, at its unit
        self.assertIn('directory board', a['why'])
        self.assertLess(math.hypot(*indoor.to_xy(a['lat'], a['lon'], *ll(35, 15))), 0.05)
        self.assertEqual(len(res['planUnits']), 6)
        pu = {u['unit']: u for u in res['planUnits']}
        self.assertEqual(pu['L2-15']['place'], next(p['folder'] for p in res['places'] if p['name'] == 'Highlands Coffee'))
        self.assertIsNone(pu['L2-31']['place'])
        self.assertFalse(pu['L2-31']['visit'])
        self.assertFalse(pu['L2-16']['visit'])
        self.assertTrue(pu['L2-30']['visit'])
        self.assertEqual(len(pu['L2-15']['polygon']), 4)
        self.assertEqual(pu['L2-15']['plan'], '_plans/plan_2_1.jpg')

    def test_plans_in_the_output(self):
        res = self.run_build()
        self.assertEqual(len(res['plans']), 1)
        p = res['plans'][0]
        self.assertEqual((p['level'], p['file'], p['source'], p['size']), ('2', '_plans/plan_2_1.jpg', 'plans/plan_2_1.jpg', [120, 200]))
        self.assertEqual(p['toMap'], self.tm)
        self.assertTrue(os.path.isfile(os.path.join(self.out, '_plans', 'plan_2_1.jpg')))
        import blur
        self.assertIn('_plans/plan_2_1.jpg', [r for r, _ in blur.photos_of(self.out)])   # blurred like every photo

    def test_phone_labels_when_no_outlines(self):
        p = read(os.path.join(self.work, 'plans.json'))
        p[0]['units'] = [{'unit': 'L2-15', 'x': 30, 'y': 170, 'h': 6}, {'unit': 'L2-40', 'x': 100, 'y': 20, 'h': 6}]
        write(os.path.join(self.work, 'plans.json'), p)
        self.spec.pop('planUnits')
        write(os.path.join(self.work, 'places.json'), self.spec)
        res = self.run_build()
        h = next(x for x in res['places'] if x['name'] == 'Highlands Coffee')['pins']['algorithm']
        self.assertEqual(h['method'], 'floor plan label')
        self.assertEqual(h['uncertaintyM'], indoor.LABEL_UNC_M)
        self.assertLess(math.hypot(*indoor.to_xy(h['lat'], h['lon'], *ll(15, 15))), 0.05)
        pu = {u['unit']: u for u in res['planUnits']}
        self.assertEqual(pu['L2-15']['from'], 'phone label')
        self.assertIsNone(pu['L2-15']['polygon'])
        self.assertFalse(pu['L2-40']['visit'])                              # no name: no visit

    def test_bad_plan_units_refused(self):
        self.spec['planUnits'] = [{'level': '2', 'plan': 'plans/nope.jpg', 'unit': 'L2-1', 'polygon': [[0, 0], [1, 1]]}]
        write(os.path.join(self.work, 'places.json'), self.spec)
        with self.assertRaises(SystemExit) as cm:
            self.run_build()
        self.assertIn('is not one of', str(cm.exception))
        self.assertIn('3+ [x, y]', str(cm.exception))


if __name__ == '__main__':
    unittest.main()
