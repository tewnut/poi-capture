"""Unit tests for the sweep processing scripts (stdlib unittest; Pillow needed for the build test).

    python -m unittest discover -s .claude/skills/process-sweep/scripts/tests -v
"""
import json, os, shutil, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import build  # noqa: E402
import enrich_browser  # noqa: E402
import enrich_web  # noqa: E402
import paths  # noqa: E402
import prepare  # noqa: E402
import result  # noqa: E402
import split  # noqa: E402


def read(path, as_json=True):
    with open(path, encoding='utf-8') as f:
        return json.load(f) if as_json else f.read()


def write(path, data):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(data if isinstance(data, str) else json.dumps(data, ensure_ascii=False))


class Splitting(unittest.TestCase):
    """split.py: a part per street, and at long stops; short runs and short parts join a neighbour."""

    @staticmethod
    def ride(spec):
        """[(road, seconds since start)] -> photos"""
        return [{'idx': i, 'roadName': r, 't': f"2026-11-19T07:{s // 60:02d}:{s % 60:02d}Z"} for i, (r, s) in enumerate(spec)]

    def test_one_street_is_one_part(self):
        self.assertEqual(split.split(self.ride([('A', i * 2) for i in range(40)])), [(0, 39)])

    def test_street_change_and_flicker(self):
        spec = [('A', i * 2) for i in range(20)] + [('X', 40), ('X', 41)] +                [('A', 42 + i) for i in range(5)] + [('B', 60 + i * 2) for i in range(20)]
        # two photos snapped to side street X are not a part; A goes on, then B
        self.assertEqual(split.split(self.ride(spec)), [(0, 26), (27, 46)])

    def test_unnamed_photos_take_the_street_around_them(self):
        spec = [('A', i) for i in range(15)] + [('Unnamed road (service)', 15 + i) for i in range(6)] + [(None, 21 + i) for i in range(3)] +                [('A', 30 + i) for i in range(15)]
        self.assertEqual(split.split(self.ride(spec)), [(0, 38)])

    def test_long_stop_cuts_the_same_street(self):
        spec = [('A', i * 2) for i in range(15)] + [('A', 600 + i * 2) for i in range(15)]   # 9 minutes apart
        self.assertEqual(split.split(self.ride(spec)), [(0, 14), (15, 29)])
        self.assertEqual(split.split(self.ride(spec), gap_min=10), [(0, 29)])

    def test_a_short_part_joins_the_one_before(self):
        spec = [('A', i * 2) for i in range(15)] + [('A', 600 + i) for i in range(4)]
        self.assertEqual(split.split(self.ride(spec)), [(0, 18)])

    def test_parts_named_like_the_phone(self):
        self.assertRegex(split.stamp('2026-11-19T07:32:00Z'), r'^2026-11-19 \d\dh32$')


class AddressParsing(unittest.TestCase):
    """Item 3: the street from a Google address whatever comes before it."""

    def check(self, addr, house, street):
        self.assertEqual(result.parse_address(addr), (house, street), addr)

    def test_plain(self):
        self.check('37 Tạ Hiện, P, Cát Lái, Hồ Chí Minh, Việt Nam', '37', 'Tạ Hiện')
        self.check('12A Tạ Hiện, P, Cát Lái, Hồ Chí Minh 700000, Việt Nam', '12A', 'Tạ Hiện')

    def test_building_before_the_street(self):
        self.check('tòa nhà One Verandah, 2 Bát Nàn, Cát Lái, Hồ Chí Minh 700000, Việt Nam', '2', 'Bát Nàn')
        self.check('SH 06 One Verandah, 02 Bát Nàn, Cát Lái, Hồ Chí Minh 71114, Việt Nam', '02', 'Bát Nàn')
        self.check('SH05, KĐT Căn hộ cao cấp dự án One Verandah, 2 Bát Nàn, Cát Lái', '2', 'Bát Nàn')
        self.check('Khu Thương Mại Feliz En Vista, 1 Phan Văn Đáng, Cát Lái', '1', 'Phan Văn Đáng')
        self.check('QQG2+XR Khu dân cư DA cty Hà Đô, 13 Tạ Hiện, P, Cát Lái', '13', 'Tạ Hiện')

    def test_street_word(self):
        self.check('Đ. Tạ Hiện, Cát Lái, Hồ Chí Minh', None, 'Tạ Hiện')
        self.check('Đ. Số 3, An Khánh, Thủ Đức', None, 'Đường Số 3')
        self.check('1 Đường 59, Cát Lái, Hồ Chí Minh', '1', 'Đường 59')
        self.check('133 -135 đường Trương Văn Bang, Cát Lái', '133-135', 'Trương Văn Bang')

    def test_where_the_name_ends(self):
        self.check('Chung cư Bộ Công An, Đ. Số 3/50 Lô A, Khu Phố 4, An Khánh', None, 'Đường Số 3')
        self.check('Đ. Số 3/48 P. Bình An, Quận 2, An Khánh', None, 'Đường Số 3')
        self.check('6B Đ. D2, P, An Khánh, Hồ Chí Minh', '6B', 'Đường D2')
        self.check('9/9 Số 9, Khu Phố 4, An Khánh', '9/9', 'Đường Số 9')
        self.check('B2 Đ. Số 3, Khu Phố 4, An Khánh', 'B2', 'Đường Số 3')
        self.check('83/20 Vành Đai Tây. phường An Khánh ( Q2 Cũ, HoChiMInh', '83/20', 'Vành Đai Tây')
        self.check('97 đường số 3 phường An Khánh quận 2 thủ đức, 97 Đ. Số 3', '97', 'Đường số 3')
        self.check('Q2 Cũ, Hồ Chí Minh', None, None)

    def test_no_street(self):
        self.check('Cát Lái, Hồ Chí Minh 700000, Việt Nam', None, None)
        self.check('Shop House, Chung cư One Verandah, Tạ Hiện, Thủ Đức', None, None)
        self.check('', None, None)

    def test_google_fields(self):
        g = {'address': 'tòa nhà One Verandah, 2 Bát Nàn, Cát Lái', 'phone': '+84 90 452 3417'}
        self.assertEqual(result._google_fields(g), {'phone': '0904523417', 'houseNumber': '2', 'street': 'Bát Nàn'})


class Comparisons(unittest.TestCase):
    """Item 13."""

    def test_phone_misread_digit_is_clarified(self):
        f = {'phone': {'value': '0904521417', 'photo': 5, 'sure': 'low'}}
        out = result.merge_source(f, {'phone': '0904523417'}, 'google')
        self.assertTrue(out['phone'].get('clarified'))
        self.assertEqual(out['phone']['value'], '0904523417')
        self.assertEqual(out['phone']['photoValue'], '0904521417')

    def test_phone_sure_reading_still_differs(self):
        f = {'phone': {'value': '0904521417', 'photo': 5, 'sure': 'high'}}
        out = result.merge_source(f, {'phone': '0904523417'}, 'google')
        self.assertEqual(out['phone']['google'], 'differs')

    def test_phone_unrelated_is_not_clarified(self):
        self.assertFalse(result._compatible('phone', '0904521417', '0281234567'))
        self.assertTrue(result._compatible('phone', '0901 457 …', '0901457801'))

    def test_long_street_reading_matches_short_google(self):
        self.assertTrue(result._same('street', 'Đường Số 3, P. An Khánh, TP. Thủ Đức', 'Đ. Số 3'))
        self.assertTrue(result._same('street', 'Tạ Hiện, Phường Cát Lái', 'Tạ Hiện'))
        self.assertTrue(result._same('street', 'Đường 3 - P. An Khánh - TP. Thủ Đức', 'Đường Số 3'))
        self.assertTrue(result._same('street', '…Số 3, KP. 4, P. Bình An', 'Đ. Số 3'))
        self.assertFalse(result._same('street', 'Đường Số 3', 'Đường Số 13'))
        self.assertFalse(result._same('street', 'Tạ Hiện', 'Bát Nàn'))
        f = {'street': {'value': 'Đường Số 3, P. An Khánh, TP. Thủ Đức', 'photo': 3, 'sure': 'high'}}
        g = {'name': 'X', 'nameMatch': 1.0, 'distanceM': 10, 'address': 'Đ. Số 3, An Khánh, Thủ Đức'}
        self.assertEqual(result.merge_google(f, g)['street']['google'], 'match')


class Google(unittest.TestCase):
    """Items 6 and 7: only a result close and alike enough to merge counts."""
    near = {'status': 'found', 'name': 'Phở Vụ', 'nameMatch': 1.0, 'distanceM': 6, 'website': 'https://phovu.vn'}
    far = {'status': 'found', 'name': 'Phở Vụ Hà Nội', 'nameMatch': 1.0, 'distanceM': 1134000, 'website': 'https://x.vn'}

    def test_mergeable(self):
        self.assertTrue(result.google_mergeable(self.near))
        self.assertTrue(result.google_mergeable({**self.near, 'distanceM': 0}))
        self.assertFalse(result.google_mergeable(self.far))
        self.assertFalse(result.google_mergeable({**self.near, 'distanceM': 101}))
        self.assertFalse(result.google_mergeable({**self.near, 'nameMatch': 0.4}))
        self.assertFalse(result.google_mergeable({'status': 'not found nearby'}))

    def test_far_google_website_not_used(self):
        self.assertEqual(enrich_web.website_of({'googleMaps': self.near}), 'https://phovu.vn')
        self.assertIsNone(enrich_web.website_of({'googleMaps': self.far}))
        self.assertIsNone(enrich_web.website_of({'googleMaps': {**self.near, 'nameMatch': 0.3}}))
        self.assertEqual(enrich_web.website_of({'googleMaps': self.far, 'fields': {'website': {'value': 'own.vn'}}}), 'own.vn')

    def test_site_read_earlier_from_far_google_is_ignored(self):
        w = {'status': 'ok', 'url': 'https://www.x.vn/', 'address': '1 Tạ Hiện', 'fields': {'phone': '0901111111'}}
        self.assertEqual(result._web_fields(w, {'street': {'value': 'Tạ Hiện'}}, self.far), {})

    def places(self, g):
        return [{'id': 's-01', 'name': 'Phở Vụ', 'status': 'open', 'confidence': 'high', 'photos': [],
                 'pins': {'algorithm': {'lat': 10.0, 'lon': 106.0}}, 'google': g}]

    def test_far_match_gets_a_visit_task(self):
        t = result.tasks_for(self.places(self.far), [])
        self.assertEqual(len(t), 1)
        self.assertIn('not on Google Maps nearby', t[0]['why'])
        self.assertEqual(result.tasks_for(self.places(self.near), []), [])
        self.assertEqual(len(result.tasks_for(self.places({'status': 'not found nearby'}), [])), 1)
        self.assertEqual(result.tasks_for(self.places({'status': 'error: timeout'}), []), [])

    def test_far_match_not_merged(self):
        f = {'name': {'value': 'Phở Vụ'}}
        self.assertEqual(result.merge_google(f, {**self.far, 'phone': '0901111111'}), f)


class BrowserRecheck(unittest.TestCase):
    """Item 5: the detail page's position is checked against the 300 m limit again."""

    def test_far_on_its_own_page_is_rejected(self):
        pick = {'name': 'Phở Vụ', 'distanceM': 40, 'nameMatch': 1.0}
        cands = [dict(pick)]
        det = {'lat': 21.03, 'lon': 105.85, 'address': 'Hà Nội'}            # ~1,100 km north
        self.assertIsNone(enrich_browser.checked(pick, det, 10.78, 106.77, cands))
        self.assertGreater(cands[0]['distanceM'], 1_000_000)
        self.assertIn('rejected', cands[0]['summary'])

    def test_near_is_kept_with_the_page_distance(self):
        pick = {'name': 'Phở Vụ', 'distanceM': 40, 'nameMatch': 1.0}
        g = enrich_browser.checked(pick, {'lat': 10.7801, 'lon': 106.77, 'phone': '090'}, 10.78, 106.77)
        self.assertEqual(g['distanceM'], 11)
        self.assertEqual(g['phone'], '090')

    def test_session_name(self):
        a = enrich_browser.session_name('C:/x/Tạ Hiện [261116]')
        self.assertRegex(a, r'^poi-ta-hien-261116-[0-9a-f]{6}$')
        self.assertNotEqual(a, enrich_browser.session_name('C:/y/Tạ Hiện [261116]'))


class Paths(unittest.TestCase):
    """Items 9 and 12."""

    def test_msys_path(self):
        self.assertEqual(paths.arg_path('/c/Users/x/Tạ Hiện [261116]', windows=True), 'C:/Users/x/Tạ Hiện [261116]')
        self.assertEqual(paths.arg_path('/d', windows=True), 'D:/')
        self.assertEqual(paths.arg_path('C:/Users/x', windows=True), 'C:/Users/x')
        self.assertEqual(paths.arg_path('rel/dir', windows=True), 'rel/dir')
        self.assertEqual(paths.arg_path('/c/Users/x', windows=False), '/c/Users/x')
        with self.assertRaises(SystemExit):
            paths.arg_path('/tmp/work', windows=True)

    def test_numeric_order(self):
        d = tempfile.mkdtemp()
        try:
            for n in ('01_a', '09_b', '10_c', '100_d', '11_e', '99_f', 'notes'):
                os.makedirs(os.path.join(d, n))
                if n != 'notes':
                    write(os.path.join(d, n, 'place.json'), '{}')
            self.assertEqual(paths.place_folders(d), ['01_a', '09_b', '10_c', '11_e', '99_f', '100_d'])
            self.assertEqual(paths.folder_no('104_Highlands_Coffee'), '104')
            self.assertEqual(paths.folder_no('07_Phở_Vụ'), '07')
        finally:
            shutil.rmtree(d)


class Roads(unittest.TestCase):
    """Items 1 and 2: the place's own road, and the road as a street suggestion."""

    def test_pick_road(self):
        self.assertEqual(result.pick_road('Tạ Hiện', ['Trương Văn Bang'], 'Trương Văn Bang / Tạ Hiện'), ('Tạ Hiện', 'cover photo'))
        self.assertEqual(result.pick_road(None, ['A', 'B', 'B', None], 'X'), ('B', 'photos'))
        self.assertEqual(result.pick_road('Unnamed road (residential)', ['Unnamed road (residential)', 'Đường Số 3'], None),
                         ('Đường Số 3', 'photos'))
        self.assertEqual(result.pick_road(None, [], 'Vành Đai Tây'), ('Vành Đai Tây', 'ride'))
        self.assertEqual(result.pick_road(None, [], 'Trương Văn Bang / Tạ Hiện'), (None, None))

    def test_ride_street_only_when_nobody_gives_one(self):
        out = result.add_ride_street({'name': {'value': 'X'}}, 'Tạ Hiện')
        self.assertEqual(out['street'], {'value': 'Tạ Hiện', 'source': 'ride', 'sure': 'medium'})
        photo = {'street': {'value': 'Nguyễn Văn Kỉnh, P. Thạnh Mỹ Lợi', 'photo': 3, 'sure': 'high'}}
        out = result.add_ride_street(photo, 'Tạ Hiện')
        self.assertEqual(out['street']['value'], 'Nguyễn Văn Kỉnh, P. Thạnh Mỹ Lợi')
        self.assertEqual((out['street']['ride'], out['street']['rideValue']), ('differs', 'Tạ Hiện'))
        g = {'street': {'value': 'Bát Nàn', 'source': 'google', 'sure': 'medium'}}
        self.assertEqual(result.add_ride_street(g, 'Tạ Hiện')['street']['value'], 'Bát Nàn')
        self.assertEqual(result.add_ride_street({}, None), {})

    def test_place_road_from_old_place_json(self):
        m = {'place': {'street': 'Trương Văn Bang / Tạ Hiện'},
             'photos': [{'cover': False, 'roadName': 'Trương Văn Bang'}, {'cover': True, 'roadName': 'Tạ Hiện'}]}
        self.assertEqual(result.place_road(m), 'Tạ Hiện')
        self.assertEqual(result.place_road({'place': {'street': 'Tạ Hiện', 'streetFrom': 'photos'}}), 'Tạ Hiện')


class Bearing(unittest.TestCase):
    """Item 8: bearingOffsetDeg applies to compass-measured bearings too."""

    def tearDown(self):
        build.BEARING_OFFSET, build.BEARING_ALL = 0.0, False

    def test_set_offset_applies_to_compass(self):
        off, note = build.bearing_correction([], {'bearingOffsetDeg': -6})
        build.BEARING_OFFSET, build.BEARING_ALL = off, True
        self.assertAlmostEqual(build.bearing_of({'cameraBearing': 90, 'cameraBearingSource': 'gyroCompass'}, 0.5), 84)
        self.assertIn('compass', note)

    def test_automatic_offset_only_for_assumed(self):
        build.BEARING_OFFSET, build.BEARING_ALL = 10.0, False
        self.assertAlmostEqual(build.bearing_of({'cameraBearing': 90, 'cameraBearingSource': 'compass'}, 0.5), 90)
        self.assertAlmostEqual(build.bearing_of({'cameraBearing': 90, 'cameraBearingSource': 'assumed'}, 0.5), 100)
        self.assertAlmostEqual(build.bearing_of({'cameraBearing': 90, 'cameraBearingSource': 'assumed+neighbour'}, 0.5), 100)


class Sheets(unittest.TestCase):
    """Item 11."""

    def test_landscape_cells_for_landscape_photos(self):
        boxes, (W, H) = prepare.sheet_layout([(3264, 1840), (1840, 3264), (3264, 1840), (3264, 1840)])
        self.assertEqual(boxes[0][2:], (1000, 564))
        self.assertEqual(boxes[1][2:], (564, 1000))
        self.assertEqual(boxes[3][1], boxes[2][1])            # the last two landscapes share one 564-high row
        self.assertLessEqual(H, 1000 + 564)
        self.assertLessEqual(W, 2000)

    def test_one_orientation_unchanged(self):
        boxes, size = prepare.sheet_layout([(3264, 1840)] * 4)
        self.assertEqual(size, (2000, 1128))
        boxes, size = prepare.sheet_layout([(1840, 3264)] * 4)
        self.assertEqual(boxes[1], (1000, 0, 1000, 1774))


class BuildRide(unittest.TestCase):
    """build.py on a tiny made-up ride: each place gets the road of its own photos, the ride street is a last
    fallback, the road becomes a street suggestion in result.json, and 100+ places list with full numbers."""

    def setUp(self):
        from PIL import Image
        self.d = tempfile.mkdtemp()
        work, self.out = os.path.join(self.d, 'work'), os.path.join(self.d, 'out', 'ride')
        os.makedirs(work)
        photos = []
        for i in range(6):
            f = os.path.join(work, f'p{i}.jpg')
            Image.new('RGB', (64, 36), (i * 30, 90, 120)).save(f)
            photos.append({'idx': i, 'id': f'p{i}', 'file': f, 't': f'2026-11-16T10:00:0{i}Z',
                           'lat': 10.78 + i * 0.00005, 'lon': 106.77, 'accuracyM': 4, 'cameraBearing': 90,
                           'cameraBearingSource': 'gyroCompass', 'hFovDeg': 69, 'width': 64, 'height': 36,
                           'speedMps': 5, 'roadName': 'Trương Văn Bang' if i < 3 else ('Tạ Hiện' if i < 5 else None),
                           'blurry': False, 'sharpness': 10, 'travelBearing': 0})
        write(os.path.join(work, 'photos.json'), photos)
        write(os.path.join(work, 'session.json'), {'id': 's_test', 'name': 'Test', 'kind': 'sweep'})
        places = [
            {'name': 'A', 'photos': [0, 2], 'cover': 1, 'sightings': [{'photo': 1, 'x': 0.5}]},
            {'name': 'B', 'photos': [3, 4], 'cover': 4, 'sightings': [{'photo': 4, 'x': 0.5}],
             'fields': {'street': {'value': 'Tạ Hiện, Phường Cát Lái', 'photo': 4, 'sure': 'low'}}},
            {'name': 'C', 'photos': [5, 5], 'cover': 5, 'sightings': [{'photo': 5, 'x': 0.5}]},
        ] + [{'name': f'P{n}', 'photos': [3, 4], 'cover': 3, 'sightings': [{'photo': 3, 'x': 0.5}]} for n in range(4, 102)]
        write(os.path.join(work, 'places.json'),
              {'street': 'Trương Văn Bang / Tạ Hiện', 'defaultFacadeDistanceM': 10, 'places': places})
        self.work = work

    def tearDown(self):
        shutil.rmtree(self.d)

    def test_build(self):
        import contextlib, io
        with contextlib.redirect_stdout(io.StringIO()):
            build.build(self.work, self.out)
        pj = lambda d: read(os.path.join(self.out, d, 'place.json'))['place']
        self.assertEqual((pj('01_A')['street'], pj('01_A')['streetFrom']), ('Trương Văn Bang', 'cover photo'))
        self.assertEqual(pj('02_B')['street'], 'Tạ Hiện')
        self.assertEqual(pj('03_C')['street'], None)          # no road on its photo; the ride names two: none
        res = read(os.path.join(self.out, 'result.json'))
        by = {p['name']: p for p in res['places']}
        self.assertEqual(by['A']['fields']['street'], {'value': 'Trương Văn Bang', 'source': 'ride', 'sure': 'medium'})
        self.assertEqual(by['B']['fields']['street']['value'], 'Tạ Hiện, Phường Cát Lái')   # the reading stays
        self.assertEqual(by['B']['fields']['street']['ride'], 'match')
        self.assertNotIn('street', by['C']['fields'])
        ids = [p['id'] for p in res['places']]
        self.assertEqual(len(ids), len(set(ids)))              # 100+ places: no id cut to 2 digits
        self.assertEqual(ids[-1], 's_test-101')
        readme = read(os.path.join(self.out, 'README.txt'), as_json=False).splitlines()
        nums = [l.split()[0] for l in readme if l[:3].strip().isdigit()]
        self.assertEqual(nums[:3], ['01', '02', '03'])
        self.assertEqual(nums[-3:], ['99', '100', '101'])


if __name__ == '__main__':
    unittest.main()
