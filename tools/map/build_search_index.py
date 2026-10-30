#!/usr/bin/env python3
"""Build the offline place-search database for the MMI navigation.

Reads an OpenStreetMap extract (.osm.pbf) and writes a small SQLite file with
every named place, point of interest and street, plus an FTS4 index over a
normalised Arabic + English search string.

Usage: build_search_index.py egypt.osm.pbf egypt-search.db
"""
import re
import sqlite3
import sys

import osmium

PLACE_RANK = {
    "city": 100, "town": 80, "suburb": 60, "village": 55, "quarter": 50,
    "neighbourhood": 45, "hamlet": 30, "locality": 25,
}
POI_KEYS = (
    "amenity", "shop", "tourism", "leisure", "aeroway", "railway", "historic", "office",
    "healthcare", "sport", "club", "craft", "emergency", "public_transport",
)
# How much a kind of place matters when searching: airports, hospitals, malls and
# universities must come before a kiosk with a similar name.
KIND_RANK = {
    "aeroway:aerodrome": 95, "aeroway:terminal": 80,
    "amenity:hospital": 70, "healthcare:hospital": 70, "shop:mall": 70, "amenity:university": 68,
    "amenity:college": 62, "railway:station": 62, "public_transport:station": 58, "leisure:stadium": 58,
    "tourism:attraction": 56, "tourism:museum": 56, "historic:monument": 50, "tourism:hotel": 48,
    "amenity:embassy": 48, "office:diplomatic": 48, "amenity:courthouse": 45, "amenity:townhall": 45,
    "amenity:police": 45, "amenity:fire_station": 40, "leisure:sports_centre": 42, "club:sport": 40,
    "amenity:fuel": 40, "amenity:clinic": 42, "healthcare:clinic": 42, "amenity:school": 36,
    "shop:supermarket": 36, "amenity:bank": 34, "amenity:pharmacy": 32, "healthcare:pharmacy": 32,
    "amenity:place_of_worship": 26, "amenity:restaurant": 24, "amenity:cafe": 23, "amenity:fast_food": 23,
    "compound": 50, "building": 30,
}
NAME_TAGS = ("name", "name:en", "name:ar", "alt_name", "alt_name:en", "alt_name:ar", "official_name",
             "official_name:en", "short_name", "old_name", "brand", "brand:en", "brand:ar", "operator")
STREET_CLASSES = {
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
    "residential", "living_street", "service", "pedestrian",
}

_TASHKEEL = re.compile("[ؐ-ًؚ-ٰٟۖ-ۭـ]")


def normalise(text: str) -> str:
    """Same rules as PlaceSearch.normalise() on the device."""
    t = _TASHKEEL.sub("", text.lower())
    t = t.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ٱ", "ا")
    t = t.replace("ة", "ه").replace("ى", "ي").replace("ؤ", "و").replace("ئ", "ي")
    return re.sub(r"[^\w]+", " ", t).strip()


def search_text(names) -> str:
    """Normalised search string; Arabic words are also indexed without "ال" so
    "عزبي" finds "العزبي"."""
    tokens = normalise(" ".join(sorted(set(names) - {""}))).split()
    extra = [t[2:] for t in tokens if t.startswith("ال") and len(t) > 4]
    return " ".join(tokens + extra)


class Collector(osmium.SimpleHandler):
    def __init__(self, db: sqlite3.Connection):
        super().__init__()
        self.db = db
        self.seen_streets = set()
        self.rows = 0

    def _add(self, tags, lat, lon, kind, rank):
        name = tags.get("name") or tags.get("name:en") or tags.get("name:ar") or tags.get("brand")
        if not name:
            return
        name_en = tags.get("name:en", "")
        name_ar = tags.get("name:ar", "")
        names = {tags.get(k, "") for k in NAME_TAGS} | {name}
        search = search_text(names)
        if not search:
            return
        # Well-known places (with a Wikidata entry) rank a little higher.
        if tags.get("wikidata"):
            rank += 8
        self.db.execute(
            "INSERT INTO places(name, name_en, name_ar, kind, lat, lon, rank) VALUES (?,?,?,?,?,?,?)",
            (name, name_en, name_ar, kind, round(lat, 6), round(lon, 6), rank),
        )
        self.db.execute("INSERT INTO places_fts(rowid, search) VALUES (last_insert_rowid(), ?)", (search,))
        self.rows += 1

    @staticmethod
    def _classify(tags, area=False):
        place = tags.get("place")
        if place in PLACE_RANK:
            return place, PLACE_RANK[place]
        for key in POI_KEYS:
            value = tags.get(key)
            if value and value != "no":
                kind = f"{key}:{value}"
                return kind, KIND_RANK.get(kind, 20)
        if area:
            # Named gated compounds and residential areas ("Madinaty", "Al Rehab").
            if tags.get("landuse") == "residential" and "name" in tags:
                return "compound", KIND_RANK["compound"]
            if tags.get("building") and "name" in tags:
                return "building", KIND_RANK["building"]
        return None, 0

    def node(self, n):
        kind, rank = self._classify(n.tags)
        if kind and n.location.valid():
            self._add(n.tags, n.location.lat, n.location.lon, kind, rank)

    def area(self, a):
        """Malls, hospitals, universities, compounds, airports: mapped as outlines or multipolygons."""
        kind, rank = self._classify(a.tags, area=True)
        if not kind:
            return
        lats, lons = [], []
        for ring in a.outer_rings():
            for nd in ring:
                if nd.location.valid():
                    lats.append(nd.location.lat)
                    lons.append(nd.location.lon)
        if lats:
            self._add(a.tags, sum(lats) / len(lats), sum(lons) / len(lons), kind, rank + 4)

    def way(self, w):
        # Streets only: places mapped as outlines arrive through area().
        tags = w.tags
        highway = tags.get("highway")
        if highway not in STREET_CLASSES or "name" not in tags:
            return
        lats, lons = [], []
        for nd in w.nodes:
            if nd.location.valid():
                lats.append(nd.location.lat)
                lons.append(nd.location.lon)
        if not lats:
            return
        mid = len(lats) // 2
        lat, lon = lats[mid], lons[mid]
        # One entry per street name per ~1 km cell: streets are split into many ways.
        key = (normalise(tags.get("name", "")), round(lat, 2), round(lon, 2))
        if key in self.seen_streets:
            return
        self.seen_streets.add(key)
        self._add(tags, lat, lon, f"street:{highway}", 10)


def main(pbf: str, out: str) -> None:
    db = sqlite3.connect(out)
    db.executescript(
        """
        DROP TABLE IF EXISTS places;
        DROP TABLE IF EXISTS places_fts;
        CREATE TABLE places(
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL, name_en TEXT, name_ar TEXT,
            kind TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, rank INTEGER NOT NULL
        );
        CREATE VIRTUAL TABLE places_fts USING fts4(search);
        """
    )
    collector = Collector(db)
    collector.apply_file(pbf, locations=True, idx="flex_mem")
    db.execute("CREATE INDEX places_kind ON places(kind, lat, lon)")
    db.execute("CREATE INDEX places_pos ON places(lat, lon)")
    db.execute("INSERT INTO places_fts(places_fts) VALUES('optimize')")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"wrote {collector.rows} places to {out}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
