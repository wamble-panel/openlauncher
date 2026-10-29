#!/usr/bin/env python3
"""Extract offline speed limits and speed cameras for the MMI navigation.

Writes egypt-safety.db with:
  cameras(lat, lon, maxspeed, direction, source)  -- OSM highway=speed_camera + enforcement relations
  limits(id, maxspeed_fwd, maxspeed_bwd, highway, minlat, minlon, maxlat, maxlon, coords)
      -- one row per OSM way with a speed limit; coords = "lat,lon;lat,lon;..."
and prints a coverage report (also appended to $GITHUB_STEP_SUMMARY in CI).

Usage: build_road_safety.py egypt.osm.pbf egypt-safety.db
"""
import math
import os
import re
import sqlite3
import sys

import osmium

DRIVEABLE = {
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
    "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
    "residential", "living_street", "service",
}
MAJOR = {"motorway", "trunk", "primary", "secondary"}


def parse_speed(value):
    """'80', '80 km/h', '50 mph' -> km/h int; anything else -> None."""
    if not value:
        return None
    m = re.match(r"^\s*(\d+)\s*(mph|km/h|kmh)?\s*$", value)
    if not m:
        return None
    speed = int(m.group(1))
    if m.group(2) == "mph":
        speed = round(speed * 1.609)
    return speed if 5 <= speed <= 200 else None


def haversine(lat1, lon1, lat2, lon2):
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


class Collector(osmium.SimpleHandler):
    def __init__(self, db):
        super().__init__()
        self.db = db
        self.km_total = 0.0
        self.km_limited = 0.0
        self.km_major = 0.0
        self.km_major_limited = 0.0
        self.limit_ways = 0
        self.cameras = 0
        self.camera_nodes = {}

    def node(self, n):
        t = n.tags
        if t.get("highway") == "speed_camera" or t.get("enforcement") == "maxspeed" or t.get("speed_camera"):
            if n.location.valid():
                self.db.execute(
                    "INSERT INTO cameras(lat, lon, maxspeed, direction, source) VALUES (?,?,?,?,?)",
                    (round(n.location.lat, 6), round(n.location.lon, 6),
                     parse_speed(t.get("maxspeed")), t.get("direction") or t.get("camera:direction"), "osm"),
                )
                self.cameras += 1

    def way(self, w):
        hw = w.tags.get("highway")
        if hw not in DRIVEABLE:
            return
        pts = [(nd.location.lat, nd.location.lon) for nd in w.nodes if nd.location.valid()]
        if len(pts) < 2:
            return
        km = sum(haversine(*pts[i], *pts[i + 1]) for i in range(len(pts) - 1))
        self.km_total += km
        base = hw.replace("_link", "")
        if base in MAJOR:
            self.km_major += km
        fwd = parse_speed(w.tags.get("maxspeed:forward")) or parse_speed(w.tags.get("maxspeed"))
        bwd = parse_speed(w.tags.get("maxspeed:backward")) or parse_speed(w.tags.get("maxspeed"))
        if w.tags.get("oneway") in ("yes", "1", "true"):
            bwd = None
        if not fwd and not bwd:
            return
        self.km_limited += km
        if base in MAJOR:
            self.km_major_limited += km
        lats = [p[0] for p in pts]
        lons = [p[1] for p in pts]
        self.db.execute(
            "INSERT INTO limits(id, maxspeed_fwd, maxspeed_bwd, highway, minlat, minlon, maxlat, maxlon, coords) "
            "VALUES (?,?,?,?,?,?,?,?,?)",
            (w.id, fwd, bwd, hw, min(lats), min(lons), max(lats), max(lons),
             ";".join(f"{a:.6f},{b:.6f}" for a, b in pts)),
        )
        self.limit_ways += 1


def main(pbf, out):
    db = sqlite3.connect(out)
    db.executescript(
        """
        DROP TABLE IF EXISTS cameras;
        DROP TABLE IF EXISTS limits;
        CREATE TABLE cameras(id INTEGER PRIMARY KEY, lat REAL, lon REAL, maxspeed INTEGER, direction TEXT, source TEXT);
        CREATE TABLE limits(id INTEGER PRIMARY KEY, maxspeed_fwd INTEGER, maxspeed_bwd INTEGER, highway TEXT,
                            minlat REAL, minlon REAL, maxlat REAL, maxlon REAL, coords TEXT);
        """
    )
    c = Collector(db)
    c.apply_file(pbf, locations=True, idx="flex_mem")
    db.execute("CREATE INDEX limits_bbox ON limits(minlat, maxlat, minlon, maxlon)")
    db.execute("CREATE INDEX cameras_pos ON cameras(lat, lon)")
    db.commit()
    db.execute("VACUUM")
    db.close()

    def pct(a, b):
        return f"{(100 * a / b):.1f}%" if b else "n/a"

    report = "\n".join([
        "### Egypt speed-limit and camera coverage (OpenStreetMap)",
        "",
        "| Measure | Value |",
        "|---|---|",
        f"| Driveable roads | {c.km_total:,.0f} km |",
        f"| ...with a speed limit | {c.km_limited:,.0f} km ({pct(c.km_limited, c.km_total)}) |",
        f"| Major roads (motorway-secondary) | {c.km_major:,.0f} km |",
        f"| ...with a speed limit | {c.km_major_limited:,.0f} km ({pct(c.km_major_limited, c.km_major)}) |",
        f"| Road ways stored | {c.limit_ways:,} |",
        f"| Speed cameras | {c.cameras:,} |",
        "",
    ])
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(report + "\n")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
