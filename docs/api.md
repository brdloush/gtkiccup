# API

## gtkiccup.core

| | |
| --- | --- |
| `(run component & opts)` | Opens a window and drives the main loop. Blocks. Opts: `:title` (`"babashka + gtk4"`), `:width` (360), `:height` (200), `:window` (`default-window`), `:css` (a stylesheet, installed before the first render), `:on-ready` (`(f window root-node)`, once), `:on-render` (`(f window root-node)`, after every render), `:on-layout` (`(f window root-node)`, after every painted frame, once sizes are real), `:app-id`, `:app-name` (see [Desktop integration](desktop-integration.md#desktop-integration)). `component` is a fn of no args returning hiccup. |
| `(refresh!)` | Re-render on the next loop turn. For code changes; state changes do it themselves. |
| `(close!)` | Close the window and let `run` return. Safe from any thread. |
| `current` | Atom, while a window is up: `{:window ptr :tree node :thread id :stop! f :error e}`. nil otherwise. A tree node is `{:tag :props :widget :children}`; its classes, from `:class` and the tag alike, are under `:props :class` as a vector. |
| `last-error` | Atom: the last render or handler failure, `nil` after a good render. |
| `(load-css! css)` / `(load-css! css priority)` | Installs a `GtkCssProvider` for the display, so `:class` means something. Needs a display. For an app's own stylesheet, pass `:css` to `run` instead: it is installed before the first render, so no widget is ever measured unstyled. |
| `(register-widget! tag spec)` | Adds or replaces a widget tag. |
| `(register-signal! prop signal invoke)` | Adds an `:on-*` prop. `invoke` is `(fn [user-fn widget])` and decides what the handler receives. |
| `widgets` `signals` | The tables themselves, as atoms. |
| `default-window` | `{:ctor :set-content}` for a plain `GtkWindow`. |
| `chromeless-window` | The same, undecorated, for an app that draws its own top edge. |
| `(widget x)` | The GTK widget behind a lifecycle hook's event, a renderer element, or a node of `(:tree @current)`. |
| `(set-dispatch! f)` | Where data handlers and data lifecycle hooks go. `nil` turns it off. |
| `(later! f)` | Runs f on the GTK thread, soon. Safe from any thread. |
| `(on-gtk-thread! f)` / `(on-gtk-thread! f ms)` | Same, but waits for the value and rethrows. Runs inline if already there. |
| `(on-gtk-thread?)` | Whether the caller is on the loop's thread. |

## gtkiccup.ratom

| | |
| --- | --- |
| `(atom init)` | Like `clojure.core/atom`, but a change marks the UI dirty. |
| `(invalidate!)` | Mark dirty by hand. `gtkiccup.core/refresh!` is this. |
| `(set-invalidate! f)` | Wiring, called by `run`. You should not need it. |

## gtkiccup.dev

| | |
| --- | --- |
| `(auto-refresh!)` / `(auto-refresh! ms)` | Re-render on a timer, default every 100ms. |
| `(stop-auto-refresh!)` | Stop it. |
| `(watch-ns! ns)` | Watch every fn currently interned in `ns`. Returns how many. |
| `(defview name & body)` | `defn` that watches its own var. |
| `(watch-var! v)` / `(unwatch-var! v)` | One var at a time. |
| `(unwatch-all!)` | Drop every var watch. |
| `(watch-files! & paths)` | Poll `.clj` mtimes, reload, re-render. Defaults to `"src"` and `"examples"`. |
| `(stop-watching-files!)` | Stop it. |
| `(status)` | What is running, and whether a window is up. |
| `(stop!)` | Stop every dev helper. Leaves the window up. |
| `later!` `on-gtk-thread!` `on-gtk-thread?` | Moved to `gtkiccup.core`; still here so old calls work. |
| `(screenshot! path)` / `(screenshot! path widget)` / `(screenshot! path widget scale)` | Renders a widget to PNG through GSK, marshalling itself onto the GTK thread. No compositor needed. Renders at the display's scale factor by default, so a 2x screen gives twice the pixels instead of a soft upscale. Returns `{:path :scale :width :height}`. |

## gtkiccup.adw

| | |
| --- | --- |
| `window` | Pass as `(ui/run view :window adw/window)`. |
| `(toast! overlay msg)` / `(toast! overlay msg seconds)` | Posts a toast into a `:toast-overlay`. |
| `specs` | The tag -> spec map, registered on load. |
| `initialized` | `{:version "1.9" :widgets [...]}`. Requiring the namespace runs `adw_init`. |
