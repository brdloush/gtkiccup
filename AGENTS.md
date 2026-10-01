# AGENTS.md

Notes for coding agents working on gtkiccup: hiccup to native GTK4 widgets, in
babashka, through `babashka.ffi`. The diffing is Replicant's; this repo is the
GTK side. Early work: APIs change freely, so fix things properly rather than
around them.

## Layout

| path | what it is |
| --- | --- |
| `src/gtkiccup/core.clj` | public API: `run`, `refresh!`, `close!`, `later!`, `load-css!`, re-exports |
| `src/gtkiccup/widgets.clj` | the tag and signal tables, common props, `connect!` |
| `src/gtkiccup/hiccup.clj` | `normalize` (validation, text children) and `->replicant` (translation) |
| `src/gtkiccup/renderer.clj` | Replicant's `IRender` protocol for GTK |
| `src/gtkiccup/ffi.clj` | raw `defcfn` bindings |
| `src/gtkiccup/ratom.clj` | reactive atom |
| `src/gtkiccup/adw.clj` | optional libadwaita tags. Core must not depend on it |
| `src/gtkiccup/dev.clj` | dev-only helpers: auto-refresh, var/file watching, screenshots |
| `examples/` | example apps (counter, todo, monitor, weather, deck) and their pure models |
| `test/` | one script per file, run against real GTK |
| `docs/` | the long-form documentation; `docs/images/` holds the screenshots |

## Running things

```bash
bb tasks                 # every task
bb test                  # all test files, in sequence; stops at the first failure
bb test/keyed_test.clj   # one test file
bb counter               # an example (also: todo, monitor, weather, deck)
bb shot monitor          # rewrite docs/images/monitor.png (also: weather, deck)
```

Tests open real windows, so they need a display (Wayland or X11). A test file
is a plain script: it prints numbered steps, asserts, and ends with `ALL OK`.
A new test file must also be added to the `test` task in `bb.edn` and to
`docs/testing.md`.

Needs the dynamically linked babashka >= 1.13.220 and GTK4. Dependencies
resolve without a JVM.

## REPL workflow

Prefer the REPL over edit-and-rerun. `bb dev` starts an nREPL server on port
1667 with this project's classpath, and `clj-nrepl-eval -p 1667` sends code to
it. A window can stay open while you change it:

```clojure
(require '[gtkiccup.core :as ui] '[gtkiccup.ratom :as r])
(def n (r/atom 1))
(defn view [] [:vbox {:margin 16} [:label "n = " @n]])
(def app (future (ui/run #'view :title "scratch")))   ; run blocks: own thread

(swap! n inc)            ; state changes re-render by themselves
(defn view [] ...)       ; a redefinition does not ...
(ui/refresh!)            ; ... until something re-renders
(:tree @ui/current)      ; the rendered tree: {:tag :props :widget :children}
(ui/close!)              ; the clean way out
```

- Pass `#'view`, not `view`, or the running window keeps the old fn.
- `(require 'some.ns :reload)` after editing a file. `src/` changes need the
  window restarted when they touch `run` itself.
- **GTK calls only on the GTK thread** -- the thread running `ui/run`. From the
  REPL, wrap widget reads in `(ui/on-gtk-thread! #(...))`. A GTK call from the
  wrong thread does not throw; it segfaults and takes the REPL with it.
- `refresh!`, `close!` and `swap!` on a ratom are safe from any thread.
- One window at a time: `ui/current` is a single atom.
- `docs/repl.md` has the full story, including `gtkiccup.dev`'s auto-refresh
  and file watching.

## Babashka gotchas that bite

- A `defn` cannot call a later `defn` without `(declare ...)`. SCI resolves at
  load time, unlike JVM Clojure.
- A C NULL pointer is not `nil`: it is a live segment at address 0. Use
  `gtkiccup.ffi/null?`, never `nil?` or `some?`.
- `clojure.string/split` and `replace` need a regex (`#"\."`), not a string.
- A docstring inside a heredoc or a Python edit loses its `\"` escapes easily;
  the namespace then fails to load with a confusing error.

## The renderer's invariants

`docs/internals.md` explains these. Do not undo them without reading it first.

- Prop values travel boxed in a one-element vector through Replicant, so
  `false` and `nil` survive its HTML attribute rules. The renderer unboxes.
- Widgets are constructed late (on first use), ref-sunk at construction, and
  unref'd only when Replicant really removes them.
- `IRender` operations only record: changed props, and child lists in a
  mirror. GTK is touched once per render, at the end: props (batched per
  widget), then structure, then `:after-children`, then unrefs, then
  lifecycle hooks. Calling GTK live from an `IRender` op breaks one-child
  containers and specs whose `:apply` reads sibling props.

## Keep the docs in step

The README and `docs/*.md` describe the code in detail: tag and prop tables,
API tables, namespace line counts, test descriptions, measured numbers. They go
stale silently. **Whenever you change how the library behaves, or what it
offers, update the docs in the same change.**

| if you change | check |
| --- | --- |
| a widget, prop or signal (`widgets.clj`, `adw.clj`) | `docs/widgets.md` |
| a public fn in any namespace | `docs/api.md` |
| hiccup handling, the renderer, the main loop, an extension point | `docs/internals.md` (also its namespace/line-count table) |
| `run`, threads, `gtkiccup.dev` | `docs/repl.md` |
| app id, icons, `examples/desktop.clj` | `docs/desktop-integration.md` |
| an example's behaviour or look | `docs/examples.md`, and `bb shot <app>` for its picture |
| a test file | `docs/testing.md`, and the `test` task in `bb.edn` |
| something gets fixed or found missing | `docs/limitations.md` |
| install needs, footprint, the pitch | `README.md` |

Re-measure a number before you repeat it (memory, CPU, timings). Do not copy
it from an older doc.

## Style

- Comments explain *why*: the GTK quirk, the trap, the measured reason. Match
  the existing density; most namespaces open with a docstring that explains
  the design.
- Prefer a pure function plus a test over logic inside a view. The examples
  keep their models (`openmeteo`, `sysinfo`, `deckmd`) apart from the UI so
  they can be tested without a window.
