// The data the web app works on. A dataset is one imported thing: a result bundle from the agentic processing
// (result.json, schema poi-result/1, see docs/pipeline.md) or a raw session zip from the phone (schema poi-capture/3).

export type LatLon = { lat: number; lon: number }

/** A value read on the photos, or filled from the website / Google when the photos lack it (source "website" /
 *  "google"); compared with a matched Google place: google = "match" | "differs" (then googleValue is Google's).
 *  A street nobody gives is the road the photos were taken on (source "ride", a suggestion: fields.ts); ride =
 *  "differs" + rideValue when another source names a different street. An indoor walk's level not read on a sign is
 *  the walk's (source "walk": barometer / the walker's pick, docs/indoor.md). */
export type Field = {
  value: string; photo?: number | string | null; sure?: 'high' | 'medium' | 'low'
  source?: 'photo' | 'google' | 'website' | 'ride' | 'walk'; google?: 'match' | 'differs'; googleValue?: string; note?: string | null
  website?: 'match' | 'differs'; websiteValue?: string
  ride?: 'match' | 'differs'; rideValue?: string
  /** an unclear photo reading made clear by the source; photoValue = what the photo showed */
  clarified?: boolean; photoValue?: string
}

export const FIELD_KEYS = ['name', 'phone', 'houseNumber', 'street', 'openingHours', 'website', 'signText', 'other', 'signCode', 'level', 'unit'] as const
export type FieldKey = (typeof FIELD_KEYS)[number]
export const FIELD_LABEL: Record<FieldKey, string> = {
  name: 'Name', phone: 'Phone', houseNumber: 'House number', street: 'Street / area', openingHours: 'Opening hours',
  website: 'Website', signText: 'Sign text', other: 'Other text', signCode: 'Sign code', level: 'Level', unit: 'Unit',
}

/** What a result entry is (docs/sweep-targets.md): a place (shop), a house number, or a traffic sign. Missing = place. */
export type Layer = 'place' | 'address' | 'sign'
export const LAYERS: Layer[] = ['place', 'address', 'sign']
/** What a ride was captured for (chosen on the phone). Missing = shops. */
export type Target = 'shops' | 'addresses' | 'signs'

export type Sighting = { i: number; lat: number; lon: number; bearing?: number }

export type Pin = LatLon & { method?: string; uncertaintyM?: number; sightings?: Sighting[]; why?: string }
export type GooglePin = LatLon & { name?: string; distanceM?: number; nameMatch?: number; url?: string }

export type PlaceStatus = 'open' | 'closed' | 'for_rent' | 'under_construction' | 'gone' | 'unknown'

export type Place = {
  id: string
  folder?: string
  name: string
  category?: string | null
  status: PlaceStatus
  confidence?: string | null
  note?: string | null
  fields: Partial<Record<FieldKey, Field>>
  photos: { file: string; index?: number | null; cover?: boolean; why?: string | null; source?: 'website'; url?: string }[]
  pins: { algorithm?: Pin; contributor?: Pin; google?: GooglePin | null; agent?: Pin | null }
  google?: Record<string, any> | null
  layer?: Layer
  /** A traffic sign: QCVN 41 code and group, which way a driver facing it drives, left / right / over the road;
   *  lane (over the road: 1 = rightmost); post = the id of the pole / gantry it shares with other signs of the ride,
   *  order = its place on it (1 = top; on a gantry the leftmost board). See signPosts.ts. */
  sign?: { code?: string; group?: string; facingDeg?: number | null; side?: string; lane?: number | null
    post?: string | null; order?: number | null }
  /** An address: the place (shop) whose house number it is, when there is one. */
  placeRef?: string | null
  /** An address, optional (newer processing): where the number was read (plate | sign | painted | other), the old
   *  number on the same plate, other numbers on the gate, read only in part. Read by addresses.ts addressExtras. */
  numbering?: { source?: string | null; oldNumber?: string | null; altNumbers?: string[] | null; partial?: boolean } | null
  /** A shop on an indoor walk (docs/indoor.md): its level as the building names it ("B1", "G", "2"; levelFrom
   *  read on a sign, or the walk's), its unit ("L2-15" as read), the building it is in, and the directory-board entry
   *  that confirms it. The reviewed values are the level / unit fields (indoor.ts placeLevel). */
  indoor?: boolean
  level?: string | null
  levelFrom?: 'read' | 'walk' | null
  unit?: string | null
  building?: { osm?: string | null; name?: string | null; address?: string | null; addr?: Record<string, string> | null } | null
  directory?: DirectoryEntry | null
}

/** A shop read on a mall's directory board (docs/indoor.md). place = the folder of the place it matched, null when
 *  no place did (then a visit task; lat / lon = where it probably is). */
export type DirectoryEntry = { level?: string | null; unit?: string | null; name?: string | null; by?: 'unit' | 'name' | null
  photo?: string | number | null; photoIndex?: number | null; place?: string | null; lat?: number; lon?: number; where?: string }

/** The building of an indoor walk, as the phone loaded it from OpenStreetMap (session.building). Rings are
 *  [[lat, lon], ...]: outer (one or more rings) and holes when given, else footprint. addr = its addr:* tags
 *  ("addr:street": ..., the prefix may be left out). */
export type Building = {
  osm?: string | null; name?: string | null; levels?: number | null; underground?: number | null
  footprint?: [number, number][] | null; outer?: [number, number][][] | null; holes?: [number, number][][] | null
  levels_seen?: string[] | null; indoorMapped?: boolean; addr?: Record<string, string> | null
}

/** A floor plan of an indoor walk (docs/indoor.md §7): the straightened mall board (file: its image in the dataset),
 *  its size in pixels and toMap, the similarity transform from plan pixels (x right, y down) to the map: rotated
 *  rotationDeg clockwise, scaled scaleMPerPx, y flipped -> metres east / north of origin [lat, lon]. */
export type ToMap = { origin: [number, number]; scaleMPerPx: number; rotationDeg: number }
export type FloorPlan = { level?: string | null; file: string; source?: string; size: [number, number]; toMap: ToMap
  placedBy?: string | null; youAreHere?: [number, number] | null; photo?: string | null }
/** A unit read on a floor plan: its outline on the map ([lat, lon]; null when only the phone's label placed it),
 *  centre, the plan it is on (file), place = the folder of the place in it, visit = a visit task was made for it. */
export type PlanUnit = { level?: string | null; unit?: string | null; name?: string | null; plan?: string | null
  polygon?: [number, number][] | null; centre?: [number, number] | null; place?: string | null; by?: string | null
  visit?: boolean; from?: 'agent' | 'phone label' }

export const layerOf = (p: Place): Layer => p.layer || 'place'

export type Task = {
  type: 'visit' | 'verify_removal' | 'removal'
  place: string | null
  name: string
  lat: number
  lon: number
  why: string
  photo?: string | null
  /** Indoor walks: the level / unit of the shop to visit. */
  level?: string | null
  unit?: string | null
}

export type Shot = [number, number, number, number | null] | [number, number, number, number | null, string | null]

export type Dataset = {
  id: string                       // session id (+ "-raw" for a raw zip)
  source: 'result' | 'raw'
  importedAt: string
  session: { id: string; name?: string; kind?: 'sweep' | 'poi' | 'indoor'; holding?: string | null; startedAt?: string; street?: string | null; target?: Target }
  track: [number, number][]
  /** [photo index, lat, lon, camera bearing, level (indoor walks only)] */
  shots: Shot[]
  /** Indoor walks (docs/indoor.md): the building, its levels lowest first, the level of each track point, the
   *  shops read on its directory boards. */
  building?: Building | null
  levels?: string[]
  trackLevels?: (string | null)[]
  directory?: DirectoryEntry[]
  /** Indoor walks with floor plans: the plans (drawn under their level) and the units outlined on them. */
  plans?: FloorPlan[]
  planUnits?: PlanUnit[]
  shotFiles?: Record<number, string>                  // raw zips: photo index -> file path
  places: Place[]
  tasks: Task[]
  files: string[]                                     // paths of the stored photos
}

export type Decision = 'todo' | 'confirmed' | 'rejected' | 'visit'
export type PinSource = 'algorithm' | 'contributor' | 'agent' | 'manual'

/** What the reviewer decided about one place (stored per dataset id + place id). */
export type Review = {
  decision: Decision
  fields: Partial<Record<FieldKey, { state: 'ok' | 'edited' | 'rejected'; value: string }>>
  category?: string
  pin?: LatLon & { source: PinSource }
  comment?: string
  photos?: PhotoEdits
  reviewer?: string
  at?: string
}

/** Crop rectangle as fractions of the photo: [x, y, width, height]. */
export type CropRect = [number, number, number, number]

/** The reviewer's changes to a place's photos. The imported photos are never changed: a removed one is only
 *  listed, a crop is a new image stored beside the original, an added one is stored under added/. */
export type PhotoEdits = {
  removed?: string[]                                        // imported photo files left out
  added?: string[]                                          // stored paths of photos the reviewer added
  crops?: Record<string, { rect: CropRect; file: string }>  // photo file -> its cropped copy
  cover?: string                                            // photo file chosen as the cover
}

/** A removal task's outcome: confirmed (the place is gone) or turned into a check on site. */
export type RemovalReview = { decision: 'todo' | 'confirmed' | 'verify' | 'dismissed'; reviewer?: string; at?: string }
