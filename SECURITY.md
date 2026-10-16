# Security

Fettle is a client/server mod. It does not open sockets, it does not download anything at runtime, and
it does not read or write files outside the paths Minecraft already uses for configuration and logging.

## Reporting

Use GitHub's private vulnerability reporting (Security -> Report a vulnerability) for anything that
could be exploited, for example:

- a mixin that lets a remote client crash or hang a server,
- a configuration value that can be abused for a denial of service on a multiplayer server,
- an issue in the build or release pipeline.

For ordinary bugs (a crash on world load, a broken optimization, wrong behaviour), open a normal issue
with the log and the relevant config file attached.

## Supported versions

Only the latest release on the default branch is supported.
