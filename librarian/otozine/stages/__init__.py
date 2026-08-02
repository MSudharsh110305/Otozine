"""Pipeline stages.

Each stage is independently resumable: it records its own success in
`ingest_state`, reads only what earlier stages wrote, and can be re-run in
isolation by bumping its STAGE_VERSION.
"""
