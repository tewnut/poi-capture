// The data the web app works on. A dataset is one imported thing: a result bundle from the agentic processing
// (result.json, schema poi-result/1, see docs/pipeline.md) or a raw session zip from the phone (schema poi-capture/3).

export type LatLon = { lat: number; lon: number }

export type Field = { value: string; photo?: number | string | null; sure?: 'high' | 'medium' | 'low' }

export const FIELD_KEYS = ['name', 'phone', 'houseNumber', 'street', 'openingHours', 'website', 'signText', 'other'] as const
export type FieldKey = (typeof FIELD_KEYS)[number]
export const FIELD_LABEL: Record<FieldKey, string> = {
  name: 'Name', phone: 'Phone', houseNumber: 'House number', street: 'Street / area', openingHours: 'Opening hours',
  website: 'Website', signText: 'Sign text', other: 'Other text',
}

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
  photos: { file: string; index?: number | null; cover?: boolean; why?: string | null }[]
  pins: { algorithm?: Pin; contributor?: Pin; google?: GooglePin | null; agent?: Pin | null }
  google?: Record<string, any> | null
}

export type Task = {
  type: 'visit' | 'verify_removal' | 'removal'
  place: string | null
  name: string
  lat: number
  lon: number
  why: string
  photo?: string | null
}

export type Dataset = {
  id: string                       // session id (+ "-raw" for a raw zip)
  source: 'result' | 'raw'
  importedAt: string
  session: { id: string; name?: string; kind?: 'sweep' | 'poi'; holding?: string | null; startedAt?: string; street?: string | null }
  track: [number, number][]
  shots: [number, number, number, number | null][]   // [photo index, lat, lon, camera bearing]
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
  reviewer?: string
  at?: string
}

/** A removal task's outcome: confirmed (the place is gone) or turned into a check on site. */
export type RemovalReview = { decision: 'todo' | 'confirmed' | 'verify' | 'dismissed'; reviewer?: string; at?: string }
