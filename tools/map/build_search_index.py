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
POI_KEYS = ("amenity", "shop", "tourism", "leisure", "aeroway", "railway", "historic", "office")
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


class Collector(osmium.SimpleHandler):
    def __init__(self, db: sqlite3.Connection):
        super().__init__()
        self.db = db
        self.seen_streets = set()
        self.rows = 0

    def _add(self, tags, lat, lon, kind, rank):
        name = tags.get("name")
        if not name:
            return
        name_en = tags.get("name:en", "")
        name_ar = tags.get("name:ar", "")
        search = normalise(" ".join({name, name_en, name_ar} - {""}))
        if not search:
            return
        self.db.execute(
            "INSERT INTO places(name, name_en, name_ar, kind, lat, lon, rank) VALUES (?,?,?,?,?,?,?)",
            (name, name_en, name_ar, kind, round(lat, 6), round(lon, 6), rank),
        )
        self.db.execute("INSERT INTO places_fts(rowid, search) VALUES (last_insert_rowid(), ?)", (search,))
        self.rows += 1

    @staticmethod
    def _classify(tags):
        place = tags.get("place")
        if place in PLACE_RANK:
            return place, PLACE_RANK[place]
        for key in POI_KEYS:
            value = tags.get(key)
            if value:
                return f"{key}:{value}", 20
        return None, 0

    def node(self, n):
        kind, rank = self._classify(n.tags)
        if kind and n.location.valid():
            self._add(n.tags, n.location.lat, n.location.lon, kind, rank)

    def way(self, w):
        tags = w.tags
        kind, rank = self._classify(tags)
        highway = tags.get("highway")
        if not kind and highway in STREET_CLASSES and "name" in tags:
            kind, rank = f"street:{highway}", 10
        if not kind:
            return
        lats, lons = [], []
        for nd in w.nodes:
            if nd.location.valid():
                lats.append(nd.location.lat)
                lons.append(nd.location.lon)
        if not lats:
            return
        mid = len(lats) // 2
        lat, lon = (lats[mid], lons[mid]) if kind.startswith("street") else (sum(lats) / len(lats), sum(lons) / len(lons))
        if kind.startswith("street"):
            # One entry per street name per ~1 km cell: streets are split into many ways.
            key = (normalise(tags.get("name", "")), round(lat, 2), round(lon, 2))
            if key in self.seen_streets:
                return
            self.seen_streets.add(key)
        self._add(tags, lat, lon, kind, rank)


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
    db.execute("INSERT INTO places_fts(places_fts) VALUES('optimize')")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"wrote {collector.rows} places to {out}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
