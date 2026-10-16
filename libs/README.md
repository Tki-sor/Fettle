# libs/

Local, git-ignored jars used only to **compile** Fettle's Create-targeted mixins. Nothing here is
bundled into the Fettle jar.

| File | Source | Version |
| --- | --- | --- |
| `create-1.21.1-6.0.10.jar` | Modrinth: `https://cdn.modrinth.com/data/LNytGWDc/versions/UjX6dr61/create-1.21.1-6.0.10.jar` | Create 6.0.10+mc1.21.1 (19,123,767 bytes) |

Re-download:
```bash
mkdir -p libs && curl -L -o libs/create-1.21.1-6.0.10.jar \
  "https://cdn.modrinth.com/data/LNytGWDc/versions/UjX6dr61/create-1.21.1-6.0.10.jar"
```

For **runtime** testing, drop the same jar into `run/server/mods/`; Create bundles Flywheel, Ponder
and Registrate as jar-in-jar dependencies, so that single file is enough to boot a server with Create.
