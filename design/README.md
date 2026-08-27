# design/

Design-time material. None of it is compiled into the app, and none of it has to be present to
build one: every asset these tools produce is committed under `app/src/main/res/` and
`app/src/main/assets/`, and those committed files are what ships.

| | |
|---|---|
| `tools/` | the generators — the mascot slots, the world map (three treatments, one drawable), the 252 country flags |
| `data/ne_110m_land.geojson` | Natural Earth 1:110m land polygons, public domain: the input the map is computed from |
| `reference/` | **not published.** The owner's own drawings and mockups — working material rather than part of the app |

`reference/` being absent from this repository is deliberate, and it costs nothing that ships.
Of the five generators only `make_mascot_assets.py` opens it; `make_wireframe_map.py` (the one
whose output is the map the app actually draws), `make_network_map.py` and
`make_worldmap_asset.py` need only `data/`, and `make_flag_assets.py` fetches the flags from
flagcdn.com. So the one asset that is genuinely computed rather than drawn is reproducible from
this repository alone, and re-running any generator only reproduces a file already in the tree.

Never hand-edit a generated `.webp`; change the generator and re-run it. The tools need Pillow,
and the map ones numpy and scipy — design-time dependencies, absent from the app's build. Run
them from the repository root:

```bash
python design/tools/make_wireframe_map.py
python design/tools/make_flag_assets.py
```
