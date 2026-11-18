# Data and licensing

## OpenStreetMap data

`data/osm/tel-aviv-center.json.gz` is an extract of OpenStreetMap, downloaded from a public Overpass
API instance by `.github/workflows/fetch-osm.yml`.

- **© OpenStreetMap contributors.** The data is available under the
  [Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
- The extract and the databases this project builds from it (every release in PostGIS) are derived
  databases, and are shared under the same licence.
- The demo map shows the attribution, and uses OpenStreetMap's standard tile server, which is meant
  for light use such as this demo.
- The committed extract's data timestamp is in the release report and in `GET /v1/dataset`.

## Synthetic data

- `data/synthetic/regulatory_zones.geojson`: the regulatory zones (`zone-a`, `zone-b`) are fictional.
  They exist to show how a regulatory boundary is published, validated and rolled back. Every API
  response marks them `"origin": "synthetic"`.
- `data/fixtures/`: hand-made test data in OpenStreetMap's JSON shape. It is not OpenStreetMap data.

## Code

The code is MIT-licensed (`LICENSE`). The licence does not cover the OpenStreetMap data above.
