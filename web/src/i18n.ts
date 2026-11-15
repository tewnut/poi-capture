// Interface language: English or Vietnamese (kept in this browser). Data (place names, what was read on the
// photos, the agent's notes) is shown as it is.
import { ref } from 'vue'

export type Lang = 'en' | 'vi'
export const LANGS: { key: Lang; label: string }[] = [{ key: 'en', label: 'English' }, { key: 'vi', label: 'Tiếng Việt' }]

function initial(): Lang {
  try {
    const s = localStorage.getItem('lang')
    if (s === 'en' || s === 'vi') return s
  } catch { /* storage blocked */ }
  return navigator.language?.toLowerCase().startsWith('vi') ? 'vi' : 'en'
}
export const lang = ref<Lang>(initial())
export function setLang(l: Lang) {
  lang.value = l
  try { localStorage.setItem('lang', l) } catch { /* storage blocked */ }
  document.documentElement.lang = l
}
document.documentElement.lang = lang.value

type Msg = string | ((p: Record<string, any>) => string)
const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

const en: Record<string, Msg> = {
  appName: 'POI Capture Reviewer',
  settings: 'Settings', reviewer: 'Reviewer', reviewerHint: 'Your name, saved with each decision',
  yourName: 'your name', language: 'Language', close: 'Close',
  sessions: 'Sessions', allTasks: 'All tasks', places: 'Places', tasks: 'Tasks', back: 'Sessions',
  loading: 'Loading the sessions saved in this browser…',
  noSessions: 'No sessions yet',
  importIntro: 'Import one of these, with the buttons below or by dropping it anywhere on the page:',
  importProcessed: 'A processed session folder',
  importProcessedRest: '(with result.json), from the process-sweep skill, or a zip of it: places ready to review.',
  importRaw: 'A zip exported from the phone',
  importRawRest: ': the ride and its photos, or the places of a Place capture, not processed yet.',
  staysLocal: 'Everything stays in this browser; nothing is uploaded.',
  anyStage: 'Any stage', allKinds: 'All kinds', sweep: 'Street sweep', poi: 'Place capture',
  mountAndHand: 'Mounted + in hand', mounted: 'Mounted', inHand: 'In hand',
  noSessionMatch: 'No session matches these filters',
  sessionsHidden: (p) => `${plural(p.n, 'session is', 'sessions are')} hidden by them.`,
  clearFilters: 'Clear filters',
  showOnMap: 'Show on the map', removeSession: 'Remove from this browser',
  removeConfirm: (p) => `Remove "${p.name}" and its reviews from this browser?`,
  noDate: 'no date',
  kindPoi: 'Place capture', kindSweepHand: 'Street sweep, in hand', kindSweepMount: 'Street sweep, mounted',
  nPlaces: (p) => plural(p.n, 'place', 'places'), noPlacesYet: 'no places yet',
  newToGoogle: (p) => `${p.n} new to Google`, nTasks: (p) => plural(p.n, 'task', 'tasks'),
  importFolder: 'Import folder', importZip: 'Import zip', orDrop: 'or drop it on the page',
  importing: (p) => `Importing ${plural(p.n, 'file', 'files')}…`,
  nothingToImport: 'Nothing to import: pick a processed session folder (with result.json), a zip of it, or a phone export zip.',
  dropHere: 'Drop a processed session folder, its zip, or a phone export zip',
  missingLink: (p) => `This link points to ${p.what}, which is not in this browser. Import that session (its folder or zip), then open the link again.`,
  // stages
  stageCaptured: 'Captured', stageProcessed: 'Agent processed', stageReviewed: 'Human reviewed',
  reviewedOf: (p) => `${p.done}/${p.total} reviewed`, notProcessed: 'not processed yet',
  // places
  search: 'Search name, category, sign', anyDecision: 'Any decision', toReview: 'To review', confirmedF: 'Confirmed',
  needsVisit: 'Needs a visit', rejectedF: 'Rejected', googleAny: 'Google: any', onGoogle: 'On Google',
  notOnGoogle: 'Not on Google', notChecked: 'Not checked',
  decTodo: 'to review', decConfirmed: 'confirmed', decRejected: 'rejected', decVisit: 'needs a visit',
  notOnGoogleChip: 'not on Google',
  notProcessedTitle: 'Not processed yet',
  notProcessedText: 'This is a street sweep straight from the phone: its places are found by the processing step. You can look at the ride and its photo positions on the map.',
  notProcessedHint: 'Process it with the process-sweep skill, then import the processed folder (or its zip).',
  noPlacesPoi: 'No places in this session', noPlacesPoiText: 'No place was captured, or none had photos.',
  noPlacesFound: 'No places found', noPlacesFoundText: 'The processing found no business on this ride.',
  noPlaceMatch: 'No place matches these filters', placesHidden: (p) => `${plural(p.n, 'place is', 'places are')} hidden by them.`,
  // tasks
  noTasksYet: 'No tasks yet',
  noTasksYetText: 'Tasks come with processed street sweeps: visits for places to check with Place capture (new to Google, changed, or partly read) and removals for places seen closed or gone. Import a processed session first.',
  nothingToDo: 'Nothing to do',
  nothingToDoText: 'The imported sessions have no tasks: every place is on Google and was read fully, and none was seen closed.',
  noTasksShown: 'No tasks in the sessions shown',
  tasksHidden: (p) => `${plural(p.n, 'task is', 'tasks are')} in sessions hidden from the map. Tick them in Sessions to list their tasks.`,
  goSessions: 'Go to Sessions', allTasksHint: 'Tasks of the sessions shown on the map, for planning visits.',
  export: 'Export', exportTitle: 'Download these tasks as GeoJSON',
  sessionTasksHint: 'Visits go to Place capture on the phone; removals are confirmed here or checked on site.',
  noTasksSession: 'No tasks in this session',
  noTasksSessionRaw: 'Tasks come from the processing step: process this session first.',
  noTasksSessionDone: 'Every place is on Google and was read fully, and none was seen closed.',
  taskVisit: 'visit', taskRemoval: 'removal', taskVerify: 'verify removal', placeLink: 'place ›', showPlace: 'Show the place',
  confirmRemoval: 'Confirm removal', checkOnSite: 'Check on site', dismiss: 'Dismiss',
  removalConfirmed: 'removal confirmed', willCheck: 'check on site', dismissed: 'dismissed',
  taskTypeVisit: 'Visit (Place capture)', taskTypeRemoval: 'Removal', taskTypeVerify: 'Verify removal on site',
  why: 'Why', visitHowTo: 'Go there with Place capture: confirm the name, take the sign, the door and the opening hours, and set the pin at the doorway.',
  openPlace: 'Open the place', openOsm: 'Open on OpenStreetMap', by: 'by',
  'why.notOnGoogle': 'not on Google Maps nearby', 'why.partly': 'name only partly read',
  // map
  mapEmptyTitle: 'Your sessions show up here', mapEmptyText: 'Import a session to see its ride, its places and the photos that found them.',
  mapHiddenTitle: 'No session on the map', mapHiddenText: 'Tick a session in the list to show it.',
  photoN: (p) => `photo #${p.n}`, finalPinTip: 'final pin: drag to the doorway', algorithm: 'algorithm', contributor: 'contributor',
  googleTip: (p) => `Google: ${p.name} (for checking only)`, agentTip: (p) => `agent: ${p.why}`,
  removalTip: (p) => `Removal: ${p.name}`, verifyTip: (p) => `Verify removal: ${p.name}`,
  osmLayer: 'OpenStreetMap', satLayer: 'Satellite (Esri)',
  // publish
  publish: 'Publish', publishTitle: (p) => `Publish ${p.name}`,
  publishCount: (p) => `${plural(p.places, 'confirmed place', 'confirmed places')} and ${plural(p.removals, 'confirmed removal', 'confirmed removals')} in this session.`,
  nothingToPublish: 'Nothing to publish yet',
  nothingToPublishText: 'Confirm places in the review panel (open a place, check its fields and pin, then Confirm), or confirm removals in Tasks.',
  publishRule: "Only what the photos showed and you confirmed is written, with the pin you chose. Google's details and pins are never included.",
  osmNote: 'The .osm file holds new nodes for JOSM: check them against what is already mapped and follow the OSM import guidelines before uploading. Removals are only in the GeoJSON and CSV.',
  nothingConfirmed: 'Nothing confirmed yet',
  // place review
  category: 'category', status_open: 'open', status_closed: 'closed', status_for_rent: 'for rent',
  status_under_construction: 'under construction', status_gone: 'gone', status_unknown: 'unknown',
  namePartly: 'name partly read', gFound: 'on Google Maps', gNew: 'not on Google nearby', gTodo: 'Google not checked',
  noPhotoKept: 'No photo was kept for this place.', noPhoto: 'no photo', fullScreen: 'Full screen', newTab: 'Open the photo in a new tab',
  prev: 'Previous (←)', next: 'Next (→)', closeEsc: 'Close (Esc)',
  whatPhotosSay: 'What the photos say', notOnPhotos: 'not on the photos', correct: 'Correct', wrongLeaveOut: 'Wrong: leave out',
  'field.name': 'Name', 'field.phone': 'Phone', 'field.houseNumber': 'House number', 'field.street': 'Street / area',
  'field.openingHours': 'Opening hours', 'field.website': 'Website', 'field.signText': 'Sign text', 'field.other': 'Other text',
  sure_medium: 'medium', sure_low: 'low',
  position: 'Position', chipAlgo: 'Algo', chipContributor: 'Contributor', chipAgent: 'Agent', chipPin: 'Pin', chipGoogle: 'Google',
  chipUse: 'Show, and use as the final pin', chipShowGoogle: 'Show (for checking only)', chipShow: 'Show',
  algorithmT: 'Algorithm', contributorT: 'Contributor', finalPin: 'final pin',
  greenLines: 'Green lines on the map: the photos it was located from.',
  fromPin: (p) => `${p.m} m from the ${p.pin} pin`, yourPin: 'Your pin',
  dragPin: 'Drag the orange pin on the map to the doorway; it becomes the final pin.', nowAt: 'Now at',
  googleMaps: 'Google Maps', checkingOnly: 'for checking only, never published', openGoogle: 'open on Google Maps',
  googleNotFound: 'Not found nearby: probably a place Google does not have.',
  comment: 'Comment (optional)', confirm: 'Confirm', confirmedBtn: '✓ Confirmed', visitQueued: 'Visit queued',
  reject: 'Reject', undo: 'Undo',
}

const vi: Record<string, Msg> = {
  appName: 'POI Capture Reviewer',
  settings: 'Cài đặt', reviewer: 'Người duyệt', reviewerHint: 'Tên của bạn, lưu cùng mỗi quyết định',
  yourName: 'tên của bạn', language: 'Ngôn ngữ', close: 'Đóng',
  sessions: 'Phiên', allTasks: 'Tất cả việc', places: 'Địa điểm', tasks: 'Việc cần làm', back: 'Các phiên',
  loading: 'Đang tải các phiên đã lưu trong trình duyệt…',
  noSessions: 'Chưa có phiên nào',
  importIntro: 'Nhập một trong các loại sau, bằng nút bên dưới hoặc kéo thả vào trang:',
  importProcessed: 'Thư mục phiên đã xử lý',
  importProcessedRest: '(có result.json), từ skill process-sweep, hoặc file zip của nó: địa điểm sẵn sàng để duyệt.',
  importRaw: 'File zip xuất từ điện thoại',
  importRawRest: ': chuyến đi và ảnh, hoặc các địa điểm của Place capture, chưa xử lý.',
  staysLocal: 'Mọi dữ liệu chỉ nằm trong trình duyệt này; không tải lên đâu cả.',
  anyStage: 'Mọi giai đoạn', allKinds: 'Mọi loại', sweep: 'Quét đường', poi: 'Chụp địa điểm',
  mountAndHand: 'Gắn xe + cầm tay', mounted: 'Gắn xe', inHand: 'Cầm tay',
  noSessionMatch: 'Không phiên nào khớp bộ lọc',
  sessionsHidden: (p) => `${p.n} phiên bị ẩn bởi bộ lọc.`,
  clearFilters: 'Xóa bộ lọc',
  showOnMap: 'Hiện trên bản đồ', removeSession: 'Xóa khỏi trình duyệt',
  removeConfirm: (p) => `Xóa "${p.name}" và các kết quả duyệt khỏi trình duyệt này?`,
  noDate: 'không có ngày',
  kindPoi: 'Chụp địa điểm', kindSweepHand: 'Quét đường, cầm tay', kindSweepMount: 'Quét đường, gắn xe',
  nPlaces: (p) => `${p.n} địa điểm`, noPlacesYet: 'chưa có địa điểm',
  newToGoogle: (p) => `${p.n} chưa có trên Google`, nTasks: (p) => `${p.n} việc`,
  importFolder: 'Nhập thư mục', importZip: 'Nhập zip', orDrop: 'hoặc kéo thả vào trang',
  importing: (p) => `Đang nhập ${p.n} file…`,
  nothingToImport: 'Không có gì để nhập: hãy chọn thư mục phiên đã xử lý (có result.json), file zip của nó, hoặc zip xuất từ điện thoại.',
  dropHere: 'Thả thư mục phiên đã xử lý, file zip của nó, hoặc zip xuất từ điện thoại',
  missingLink: (p) => `Liên kết này trỏ tới ${p.what}, phiên này chưa có trong trình duyệt. Hãy nhập phiên đó (thư mục hoặc zip) rồi mở lại liên kết.`,
  stageCaptured: 'Đã thu thập', stageProcessed: 'Agent đã xử lý', stageReviewed: 'Người đã duyệt',
  reviewedOf: (p) => `${p.done}/${p.total} đã duyệt`, notProcessed: 'chưa xử lý',
  search: 'Tìm tên, loại, biển hiệu', anyDecision: 'Mọi quyết định', toReview: 'Chưa duyệt', confirmedF: 'Đã xác nhận',
  needsVisit: 'Cần đến kiểm tra', rejectedF: 'Đã loại', googleAny: 'Google: tất cả', onGoogle: 'Có trên Google',
  notOnGoogle: 'Không có trên Google', notChecked: 'Chưa kiểm tra',
  decTodo: 'chưa duyệt', decConfirmed: 'đã xác nhận', decRejected: 'đã loại', decVisit: 'cần đến kiểm tra',
  notOnGoogleChip: 'chưa có trên Google',
  notProcessedTitle: 'Chưa xử lý',
  notProcessedText: 'Đây là phiên quét đường lấy thẳng từ điện thoại: các địa điểm do bước xử lý tìm ra. Bạn có thể xem chuyến đi và vị trí các ảnh trên bản đồ.',
  notProcessedHint: 'Xử lý bằng skill process-sweep, rồi nhập thư mục đã xử lý (hoặc file zip của nó).',
  noPlacesPoi: 'Phiên này không có địa điểm', noPlacesPoiText: 'Không địa điểm nào được chụp, hoặc không có ảnh.',
  noPlacesFound: 'Không tìm thấy địa điểm', noPlacesFoundText: 'Bước xử lý không tìm thấy cơ sở kinh doanh nào trên tuyến này.',
  noPlaceMatch: 'Không địa điểm nào khớp bộ lọc', placesHidden: (p) => `${p.n} địa điểm bị ẩn bởi bộ lọc.`,
  noTasksYet: 'Chưa có việc nào',
  noTasksYetText: 'Việc cần làm đến từ các phiên quét đường đã xử lý: đi kiểm tra (địa điểm mới với Google, thay đổi, hoặc đọc chưa rõ) bằng Place capture, và gỡ bỏ (địa điểm thấy đã đóng cửa hoặc không còn). Hãy nhập một phiên đã xử lý trước.',
  nothingToDo: 'Không có việc gì',
  nothingToDoText: 'Các phiên đã nhập không có việc: mọi địa điểm đều có trên Google và đọc rõ, không nơi nào đóng cửa.',
  noTasksShown: 'Không có việc trong các phiên đang hiện',
  tasksHidden: (p) => `${p.n} việc nằm trong các phiên đang ẩn khỏi bản đồ. Tích chọn chúng ở mục Phiên để xem.`,
  goSessions: 'Đến mục Phiên', allTasksHint: 'Việc của các phiên đang hiện trên bản đồ, để lên kế hoạch đi kiểm tra.',
  export: 'Xuất', exportTitle: 'Tải các việc này dạng GeoJSON',
  sessionTasksHint: 'Đi kiểm tra bằng Place capture trên điện thoại; gỡ bỏ được xác nhận ở đây hoặc kiểm tra tại chỗ.',
  noTasksSession: 'Phiên này không có việc',
  noTasksSessionRaw: 'Việc cần làm đến từ bước xử lý: hãy xử lý phiên này trước.',
  noTasksSessionDone: 'Mọi địa điểm đều có trên Google và đọc rõ, không nơi nào đóng cửa.',
  taskVisit: 'đi kiểm tra', taskRemoval: 'gỡ bỏ', taskVerify: 'kiểm tra gỡ bỏ', placeLink: 'địa điểm ›', showPlace: 'Xem địa điểm',
  confirmRemoval: 'Xác nhận gỡ bỏ', checkOnSite: 'Kiểm tra tại chỗ', dismiss: 'Bỏ qua',
  removalConfirmed: 'đã xác nhận gỡ bỏ', willCheck: 'kiểm tra tại chỗ', dismissed: 'đã bỏ qua',
  taskTypeVisit: 'Đi kiểm tra (Place capture)', taskTypeRemoval: 'Gỡ bỏ', taskTypeVerify: 'Kiểm tra gỡ bỏ tại chỗ',
  why: 'Lý do', visitHowTo: 'Đến đó với Place capture: xác nhận tên, chụp biển hiệu, cửa và giờ mở cửa, đặt ghim ở cửa ra vào.',
  openPlace: 'Mở địa điểm', openOsm: 'Mở trên OpenStreetMap', by: 'bởi',
  'why.notOnGoogle': 'không có trên Google Maps gần đó', 'why.partly': 'tên chỉ đọc được một phần',
  mapEmptyTitle: 'Các phiên sẽ hiện ở đây', mapEmptyText: 'Nhập một phiên để xem chuyến đi, các địa điểm và ảnh đã tìm ra chúng.',
  mapHiddenTitle: 'Không có phiên nào trên bản đồ', mapHiddenText: 'Tích chọn một phiên trong danh sách để hiện.',
  photoN: (p) => `ảnh #${p.n}`, finalPinTip: 'ghim cuối: kéo đến cửa ra vào', algorithm: 'thuật toán', contributor: 'người đóng góp',
  googleTip: (p) => `Google: ${p.name} (chỉ để đối chiếu)`, agentTip: (p) => `agent: ${p.why}`,
  removalTip: (p) => `Gỡ bỏ: ${p.name}`, verifyTip: (p) => `Kiểm tra gỡ bỏ: ${p.name}`,
  osmLayer: 'OpenStreetMap', satLayer: 'Vệ tinh (Esri)',
  publish: 'Xuất bản', publishTitle: (p) => `Xuất bản ${p.name}`,
  publishCount: (p) => `${p.places} địa điểm và ${p.removals} gỡ bỏ đã xác nhận trong phiên này.`,
  nothingToPublish: 'Chưa có gì để xuất bản',
  nothingToPublishText: 'Hãy xác nhận địa điểm ở khung duyệt (mở địa điểm, kiểm tra thông tin và ghim, rồi Xác nhận), hoặc xác nhận gỡ bỏ ở mục Việc cần làm.',
  publishRule: 'Chỉ ghi những gì ảnh cho thấy và bạn đã xác nhận, với ghim bạn chọn. Không bao giờ ghi thông tin hay ghim của Google.',
  osmNote: 'File .osm chứa các điểm mới cho JOSM: đối chiếu với dữ liệu đã có và làm theo hướng dẫn nhập dữ liệu của OSM trước khi tải lên. Gỡ bỏ chỉ có trong GeoJSON và CSV.',
  nothingConfirmed: 'Chưa xác nhận gì',
  category: 'loại', status_open: 'đang mở', status_closed: 'đã đóng cửa', status_for_rent: 'cho thuê',
  status_under_construction: 'đang xây dựng', status_gone: 'không còn', status_unknown: 'không rõ',
  namePartly: 'tên đọc được một phần', gFound: 'có trên Google Maps', gNew: 'chưa có trên Google gần đó', gTodo: 'chưa kiểm tra Google',
  noPhotoKept: 'Không giữ ảnh nào cho địa điểm này.', noPhoto: 'không có ảnh', fullScreen: 'Toàn màn hình', newTab: 'Mở ảnh trong tab mới',
  prev: 'Trước (←)', next: 'Sau (→)', closeEsc: 'Đóng (Esc)',
  whatPhotosSay: 'Thông tin từ ảnh', notOnPhotos: 'không có trên ảnh', correct: 'Đúng', wrongLeaveOut: 'Sai: bỏ ra',
  'field.name': 'Tên', 'field.phone': 'Điện thoại', 'field.houseNumber': 'Số nhà', 'field.street': 'Đường / khu vực',
  'field.openingHours': 'Giờ mở cửa', 'field.website': 'Website', 'field.signText': 'Chữ trên biển', 'field.other': 'Chữ khác',
  sure_medium: 'tương đối', sure_low: 'không chắc',
  position: 'Vị trí', chipAlgo: 'Thuật toán', chipContributor: 'Người đóng góp', chipAgent: 'Agent', chipPin: 'Ghim', chipGoogle: 'Google',
  chipUse: 'Xem, và dùng làm ghim cuối', chipShowGoogle: 'Xem (chỉ để đối chiếu)', chipShow: 'Xem',
  algorithmT: 'Thuật toán', contributorT: 'Người đóng góp', finalPin: 'ghim cuối',
  greenLines: 'Đường màu xanh trên bản đồ: các ảnh dùng để định vị.',
  fromPin: (p) => `cách ghim ${p.pin} ${p.m} m`, yourPin: 'Ghim của bạn',
  dragPin: 'Kéo ghim màu cam trên bản đồ đến cửa ra vào; nó sẽ thành ghim cuối.', nowAt: 'Hiện ở',
  googleMaps: 'Google Maps', checkingOnly: 'chỉ để đối chiếu, không bao giờ xuất bản', openGoogle: 'mở trên Google Maps',
  googleNotFound: 'Không thấy gần đó: có thể là địa điểm Google chưa có.',
  comment: 'Ghi chú (không bắt buộc)', confirm: 'Xác nhận', confirmedBtn: '✓ Đã xác nhận', visitQueued: 'Đã đưa vào danh sách kiểm tra',
  reject: 'Loại', undo: 'Hoàn tác',
}

const M: Record<Lang, Record<string, Msg>> = { en, vi }

export function t(key: string, p: Record<string, any> = {}): string {
  const m = M[lang.value][key] ?? en[key]
  if (m == null) return key
  return typeof m === 'function' ? m(p) : m
}

/** Task reasons are written by the processing step in English ("a; b"): translate the known parts. */
export function tWhy(why: string): string {
  const known: Record<string, string> = { 'not on Google Maps nearby': 'why.notOnGoogle', 'name only partly read': 'why.partly' }
  return why.split('; ').map((s) => (known[s] ? t(known[s]) : s)).join('; ')
}
