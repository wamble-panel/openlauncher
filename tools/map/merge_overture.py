#!/usr/bin/env python3
"""Add Overture Maps places to the offline place-search database.

Overture (Meta, Microsoft, Amazon, TomTom) lists far more Egyptian shops,
restaurants, clinics and offices than OpenStreetMap. Places already in the
database from OSM (same name within ~150 m) are skipped.

Usage: merge_overture.py places.geojsonseq egypt-search.db
(places.geojsonseq from: overturemaps download --type=place -f geojsonseq --bbox=...)
"""
import json
import sqlite3
import sys

from build_search_index import KIND_RANK, normalise, search_text

MIN_CONFIDENCE = 0.6

# Overture categories mapped onto the OSM kinds the app already understands
# (nearby fuel, hospitals... and the same importance ranking).
CATEGORY_KIND = {
    "airport": "aeroway:aerodrome", "hospital": "amenity:hospital", "shopping_center": "shop:mall",
    "college_university": "amenity:university", "train_station": "railway:station", "metro_station": "railway:station",
    "stadium_arena": "leisure:stadium", "museum": "tourism:museum", "landmark_and_historical_building": "historic:monument",
    "hotel": "tourism:hotel", "embassy": "amenity:embassy", "police_department": "amenity:police",
    "fire_department": "amenity:fire_station", "gas_station": "amenity:fuel", "medical_center": "amenity:clinic",
    "doctor": "amenity:clinic", "school": "amenity:school", "supermarket": "shop:supermarket",
    "grocery_store": "shop:supermarket", "bank_credit_union": "amenity:bank", "atms": "amenity:atm",
    "pharmacy": "amenity:pharmacy", "mosque": "amenity:place_of_worship", "church_cathedral": "amenity:place_of_worship",
    "restaurant": "amenity:restaurant", "cafe": "amenity:cafe", "coffee_shop": "amenity:cafe",
    "fast_food_restaurant": "amenity:fast_food", "parking": "amenity:parking", "gym": "leisure:fitness_centre",
}


def obj(value):
    """Overture fields arrive as objects or as JSON text depending on the exporter."""
    if isinstance(value, str):
        try:
            return json.loads(value)
        except ValueError:
            return value
    return value


def main(src: str, out: str) -> None:
    db = sqlite3.connect(out)
    # Existing names on a ~110 m grid, to skip places OSM already has.
    seen = {}
    for name, name_en, lat, lon in db.execute("SELECT name, name_en, lat, lon FROM places"):
        cell = (round(lat * 1000), round(lon * 1000))
        bucket = seen.setdefault(cell, set())
        bucket.add(normalise(name))
        if name_en:
            bucket.add(normalise(name_en))

    def known(key, lat, lon):
        cy, cx = round(lat * 1000), round(lon * 1000)
        return any(key in seen.get((cy + dy, cx + dx), ()) for dy in (-1, 0, 1) for dx in (-1, 0, 1))

    added = skipped = 0
    with open(src, encoding="utf-8") as f:
        for line in f:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            try:
                feature = json.loads(line)
            except ValueError:
                continue
            props = feature.get("properties") or {}
            geom = feature.get("geometry") or {}
            if geom.get("type") != "Point":
                continue
            lon, lat = geom["coordinates"][:2]
            if (props.get("confidence") or 0) < MIN_CONFIDENCE:
                continue
            addresses = obj(props.get("addresses")) or []
            countries = {a.get("country") for a in addresses if isinstance(a, dict) and a.get("country")}
            if countries and "EG" not in countries:
                continue
            names = obj(props.get("names")) or {}
            primary = names.get("primary") if isinstance(names, dict) else None
            if not primary:
                continue
            common = names.get("common") or {}
            if isinstance(common, list):  # some exports: [{"key": "en", "value": ...}]
                common = {c.get("key"): c.get("value") for c in common if isinstance(c, dict)}
            name_en = common.get("en", "") or ""
            name_ar = common.get("ar", "") or ""
            brand = obj(props.get("brand")) or {}
            brand_name = ((brand.get("names") or {}).get("primary") or "") if isinstance(brand, dict) else ""
            key = normalise(primary)
            if not key or known(key, lat, lon) or (name_en and known(normalise(name_en), lat, lon)):
                skipped += 1
                continue
            categories = obj(props.get("categories")) or {}
            category = (categories.get("primary") if isinstance(categories, dict) else None) or "place"
            kind = CATEGORY_KIND.get(category, f"place:{category}")
            rank = KIND_RANK.get(kind, 18)
            search = search_text({primary, name_en, name_ar, brand_name})
            db.execute(
                "INSERT INTO places(name, name_en, name_ar, kind, lat, lon, rank) VALUES (?,?,?,?,?,?,?)",
                (primary, name_en, name_ar, kind, round(lat, 6), round(lon, 6), rank),
            )
            db.execute("INSERT INTO places_fts(rowid, search) VALUES (last_insert_rowid(), ?)", (search,))
            seen.setdefault((round(lat * 1000), round(lon * 1000)), set()).add(key)
            added += 1
    db.execute("INSERT INTO places_fts(places_fts) VALUES('optimize')")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"added {added} Overture places, skipped {skipped} already known")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
