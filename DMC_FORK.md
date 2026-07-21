# DMC Android llama.cpp Fork

This branch contains the llama.cpp, Android example, and Web UI changes used by
the DMC Android application in `inetconnector/DMC`.

- Upstream repository: `https://github.com/ggml-org/llama.cpp`
- Fork repository: `https://github.com/inetconnector/llama.cpp`
- Upstream base commit: `b3181d5be269c3a4821bac63e0bf3289e8b87411`
- Integration branch: `dmc-android`

The DMC repository consumes this branch as a pinned Git submodule. Generated
Web UI output under `tools/server/public/` is intentionally not committed; the
DMC build prepares it from `tools/ui/` before packaging the Android app.

Keep the upstream MIT license and all third-party notices intact when updating
the fork. Upstream changes should be fetched from the `upstream` remote and
merged or rebased deliberately, followed by a full Android release build.
