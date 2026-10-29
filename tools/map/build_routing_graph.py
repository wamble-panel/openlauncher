#!/usr/bin/env python3
"""Build the offline road graph for MMI turn-by-turn navigation.

Reads an OSM extract and writes egypt-routing.graph, a little-endian binary
file the app memory-maps (see RoutingGraph.kt for the reader):

  header (256 bytes, int32 fields)
    0 magic 'MMIR', 1 version, 2 nodes, 3 usegs, 4 dedges, 5 geom points,
    6 names, 7 name bytes, 8 grid keys, 9 grid entries,
    10 grid min lat e6, 11 grid min lon e6, 12 grid cell e6, 13 grid cols,
    14.. section offsets (see SECTIONS)
  nodes      lat/lon (int32 e6), first outgoing directed edge (CSR, N+1)
  dedges     target node, useg id | (reverse << 31)
  usegs      from, to, length (decimetres), geometry start/count, name index,
             packed = speed km/h | class << 8 | flags << 16 | maxspeed << 24
             flags: 1 oneway forward, 2 oneway backward, 4 roundabout, 8 link
  geometry   interior points of every useg, lat/lon int32 e6
  names      offsets (M+1) + UTF-8 blob, each "name\\x1fname:en\\x1fref"
  grid       sorted cell keys, offsets (K+1), useg ids, for snapping GPS

Usage: build_routing_graph.py egypt.osm.pbf egypt-routing.graph
"""
import math
import struct
import sys
from array import array

import numpy as np
import osmium

CLASSES = [
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
    "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
    "residential", "living_street", "service", "road",
]
CLASS_IDX = {c: i for i, c in enumerate(CLASSES)}
DEFAULT_KMH = {
    "motorway": 100, "motorway_link": 55, "trunk": 85, "trunk_link": 45, "primary": 65,
    "primary_link": 40, "secondary": 55, "secondary_link": 35, "tertiary": 45,
    "tertiary_link": 30, "unclassified": 35, "residential": 25, "living_street": 12,
    "service": 15, "road": 25,
}
SECTIONS = [
    "nodeLat", "nodeLon", "nodeFirst", "dedgeTarget", "dedgeUseg",
    "usegFrom", "usegTo", "usegLen", "usegGeomStart", "usegGeomCount", "usegName", "usegPacked",
    "geomLat", "geomLon", "nameOff", "nameBlob", "gridKeys", "gridOff", "gridEntries",
]
GRID_CELL_E6 = 5000          # 0.005 degrees, about 550 m
SAMPLE_M = 250.0             # densify long segments so snapping finds them


def parse_speed(value):
    if not value:
        return 0
    v = value.strip().lower().replace("km/h", "").replace("kmh", "").strip()
    mph = v.endswith("mph")
    v = v.replace("mph", "").strip()
    if not v.isdigit():
        return 0
    s = int(v)
    if mph:
        s = round(s * 1.609)
    return s if 5 <= s <= 200 else 0


class Ways(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.ids = array("q")
        self.lat = array("i")
        self.lon = array("i")
        self.start = array("q", [0])
        self.cls = array("B")
        self.flags = array("B")
        self.speed = array("B")
        self.maxspeed = array("B")
        self.name = array("i")
        self.name_index = {"": 0}
        self.names = [""]

    def way(self, w):
        t = w.tags
        hw = t.get("highway")
        if hw not in CLASS_IDX or t.get("area") == "yes":
            return
        access = t.get("motor_vehicle") or t.get("motorcar") or t.get("access")
        if access in ("no", "private", "agricultural", "forestry", "delivery"):
            return
        if hw == "service" and t.get("service") in ("parking_aisle", "drive-through", "emergency_access"):
            return
        refs, lats, lons = [], [], []
        for nd in w.nodes:
            if nd.location.valid():
                refs.append(nd.ref)
                lats.append(int(round(nd.location.lat * 1e6)))
                lons.append(int(round(nd.location.lon * 1e6)))
        if len(refs) < 2:
            return
        oneway = t.get("oneway")
        roundabout = t.get("junction") in ("roundabout", "circular")
        flags = 0
        if oneway in ("yes", "true", "1") or (oneway is None and (hw in ("motorway", "motorway_link") or roundabout)):
            flags |= 1
        elif oneway == "-1":
            flags |= 2
        if roundabout:
            flags |= 4
        if hw.endswith("_link"):
            flags |= 8
        maxspeed = parse_speed(t.get("maxspeed"))
        speed = DEFAULT_KMH[hw]
        if maxspeed:
            speed = max(10, min(int(maxspeed * 0.9), 130))
        key = "\x1f".join((t.get("name", ""), t.get("name:en", ""), t.get("ref", "")))
        if key == "\x1f\x1f":
            key = ""
        idx = self.name_index.get(key)
        if idx is None:
            idx = len(self.names)
            self.name_index[key] = idx
            self.names.append(key)
        self.ids.extend(refs)
        self.lat.extend(lats)
        self.lon.extend(lons)
        self.start.append(len(self.ids))
        self.cls.append(CLASS_IDX[hw])
        self.flags.append(flags)
        self.speed.append(speed)
        self.maxspeed.append(min(maxspeed, 255))
        self.name.append(idx)


def seg_lengths(lat, lon):
    """Metres between consecutive positions (last element meaningless)."""
    la = lat.astype(np.float64) / 1e6
    lo = lon.astype(np.float64) / 1e6
    p1, p2 = np.radians(la[:-1]), np.radians(la[1:])
    dp = p2 - p1
    dl = np.radians(lo[1:] - lo[:-1])
    a = np.sin(dp / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    return np.append(2 * 6371000.0 * np.arcsin(np.sqrt(np.minimum(a, 1.0))), 0.0)


def main(pbf, out):
    h = Ways()
    h.apply_file(pbf, locations=True, idx="flex_mem")
    ids = np.frombuffer(h.ids, dtype=np.int64)
    lat = np.frombuffer(h.lat, dtype=np.int32)
    lon = np.frombuffer(h.lon, dtype=np.int32)
    start = np.frombuffer(h.start, dtype=np.int64)
    n_pos = len(ids)
    n_ways = len(start) - 1
    print(f"{n_ways:,} ways, {n_pos:,} way-node positions")

    # Graph nodes: OSM nodes shared by several way positions, plus every way end.
    uniq, counts = np.unique(ids, return_counts=True)
    is_graph = counts[np.searchsorted(uniq, ids)] >= 2
    is_graph[start[:-1]] = True
    is_graph[start[1:] - 1] = True

    gp = np.nonzero(is_graph)[0]
    g_ids, first = np.unique(ids[gp], return_index=True)
    node_lat = lat[gp[first]]
    node_lon = lon[gp[first]]
    node_of_pos = np.searchsorted(g_ids, ids[gp])

    way_of_gp = np.searchsorted(start, gp, side="right") - 1
    same_way = way_of_gp[:-1] == way_of_gp[1:]
    e_p = gp[:-1][same_way]
    e_q = gp[1:][same_way]
    e_from = node_of_pos[:-1][same_way].astype(np.int32)
    e_to = node_of_pos[1:][same_way].astype(np.int32)
    e_way = way_of_gp[:-1][same_way]

    seglen = seg_lengths(lat, lon)
    cum = np.concatenate([[0.0], np.cumsum(seglen)])
    e_len_dm = np.maximum(1, np.round((cum[e_q] - cum[e_p]) * 10)).astype(np.int32)

    interior = ~is_graph
    ng_excl = np.concatenate([[0], np.cumsum(interior)])
    e_geom_start = ng_excl[e_p + 1].astype(np.int32)
    e_geom_count = (e_q - e_p - 1).astype(np.int32)
    geom_lat = lat[interior]
    geom_lon = lon[interior]

    cls = np.frombuffer(h.cls, dtype=np.uint8)[e_way].astype(np.int32)
    flags = np.frombuffer(h.flags, dtype=np.uint8)[e_way].astype(np.int32)
    speed = np.frombuffer(h.speed, dtype=np.uint8)[e_way].astype(np.int32)
    maxspeed = np.frombuffer(h.maxspeed, dtype=np.uint8)[e_way].astype(np.int32)
    e_name = np.frombuffer(h.name, dtype=np.int32)[e_way]
    packed = (speed | (cls << 8) | (flags << 16) | (maxspeed << 24)).astype(np.uint32).view(np.int32)
    n_useg = len(e_from)
    useg_ids = np.arange(n_useg, dtype=np.int64)
    print(f"{len(g_ids):,} nodes, {n_useg:,} road segments, {len(geom_lat):,} shape points")

    # Directed edges in CSR order.
    fwd = (flags & 2) == 0
    bwd = (flags & 1) == 0
    d_src = np.concatenate([e_from[fwd], e_to[bwd]])
    d_dst = np.concatenate([e_to[fwd], e_from[bwd]])
    d_useg = np.concatenate([useg_ids[fwd], useg_ids[bwd] | (1 << 31)]).astype(np.uint32).view(np.int32)
    order = np.argsort(d_src, kind="stable")
    d_src, d_dst, d_useg = d_src[order], d_dst[order].astype(np.int32), d_useg[order]
    node_first = np.searchsorted(d_src, np.arange(len(g_ids) + 1)).astype(np.int32)

    # Snapping grid: every position plus extra samples along long segments.
    min_lat = int(lat.min()) // GRID_CELL_E6 * GRID_CELL_E6
    min_lon = int(lon.min()) // GRID_CELL_E6 * GRID_CELL_E6
    cols = (int(lon.max()) - min_lon) // GRID_CELL_E6 + 2
    pos = np.arange(n_pos - 1)
    way_of_pos = np.searchsorted(start, pos, side="right") - 1
    valid = way_of_pos == (np.searchsorted(start, pos + 1, side="right") - 1)
    seg_pos = pos[valid]
    seg_useg = np.searchsorted(e_p, seg_pos, side="right") - 1
    reps = np.floor(seglen[seg_pos] / SAMPLE_M).astype(np.int64) + 1
    rp = np.repeat(seg_pos, reps)
    ru = np.repeat(seg_useg, reps)
    frac = (np.arange(len(rp)) - np.repeat(np.cumsum(reps) - reps, reps)) / np.repeat(reps, reps)
    s_lat = lat[rp] + (lat[rp + 1] - lat[rp]) * frac
    s_lon = lon[rp] + (lon[rp + 1] - lon[rp]) * frac
    s_lat = np.concatenate([s_lat, lat[e_q]])
    s_lon = np.concatenate([s_lon, lon[e_q]])
    ru = np.concatenate([ru, np.arange(n_useg)])
    keys = ((s_lat.astype(np.int64) - min_lat) // GRID_CELL_E6) * cols + (s_lon.astype(np.int64) - min_lon) // GRID_CELL_E6
    pairs = np.unique((keys << 32) | ru.astype(np.int64))
    grid_keys_all = (pairs >> 32).astype(np.int64)
    grid_entries = (pairs & 0xFFFFFFFF).astype(np.int32)
    grid_keys, grid_first = np.unique(grid_keys_all, return_index=True)
    grid_off = np.append(grid_first, len(grid_entries)).astype(np.int32)
    grid_keys = grid_keys.astype(np.int32)

    name_bytes = [n.encode("utf-8") for n in h.names]
    name_off = np.concatenate([[0], np.cumsum([len(b) for b in name_bytes])]).astype(np.int32)
    name_blob = b"".join(name_bytes)

    sections = {
        "nodeLat": node_lat.astype("<i4").tobytes(), "nodeLon": node_lon.astype("<i4").tobytes(),
        "nodeFirst": node_first.astype("<i4").tobytes(),
        "dedgeTarget": d_dst.astype("<i4").tobytes(), "dedgeUseg": d_useg.astype("<i4").tobytes(),
        "usegFrom": e_from.astype("<i4").tobytes(), "usegTo": e_to.astype("<i4").tobytes(),
        "usegLen": e_len_dm.astype("<i4").tobytes(), "usegGeomStart": e_geom_start.astype("<i4").tobytes(),
        "usegGeomCount": e_geom_count.astype("<i4").tobytes(), "usegName": e_name.astype("<i4").tobytes(),
        "usegPacked": packed.astype("<i4").tobytes(),
        "geomLat": geom_lat.astype("<i4").tobytes(), "geomLon": geom_lon.astype("<i4").tobytes(),
        "nameOff": name_off.astype("<i4").tobytes(), "nameBlob": name_blob,
        "gridKeys": grid_keys.astype("<i4").tobytes(), "gridOff": grid_off.astype("<i4").tobytes(),
        "gridEntries": grid_entries.astype("<i4").tobytes(),
    }
    offsets = []
    cursor = 256
    for s in SECTIONS:
        offsets.append(cursor)
        cursor += len(sections[s])
        cursor += (-cursor) % 4
    header = struct.pack(
        "<4s13i", b"MMIR", 1, len(g_ids), n_useg, len(d_dst), len(geom_lat), len(h.names), len(name_blob),
        len(grid_keys), len(grid_entries), min_lat, min_lon, GRID_CELL_E6, cols,
    ) + struct.pack(f"<{len(offsets)}i", *offsets)
    with open(out, "wb") as f:
        f.write(header.ljust(256, b"\0"))
        for s in SECTIONS:
            data = sections[s]
            f.write(data)
            f.write(b"\0" * ((-f.tell()) % 4))
    print(f"wrote {out}: {cursor / 1e6:.1f} MB, {len(d_dst):,} directed edges, {len(grid_keys):,} grid cells")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
