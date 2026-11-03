# API

## gtkiccup.core

| | |
| --- | --- |
| `(run component & opts)` | Opens a window and drives the main loop. Blocks. Opts: `:title` (`"babashka + gtk4"`), `:width` (360), `:height` (200), `:window` (`default-window`), `:css` (a stylesheet, installed before the first render), `:on-ready` (`(f window root-node)`, once), `:on-render` (`(f window root-node)`, after every render), `:on-layout` (`(f window root-node)`, after every painted frame, once sizes are real), `:app-id`, `:app-name` (see [Desktop integration](desktop-integration.md#desktop-integration)), `:alias-data` (a map merged into every late-bound alias expansion's attrs as `:replicant/alias-data` -- near-static config only, see [gtkiccup.alias](#gtkiccupalias)). `component` is a fn of no args returning hiccup. |
| `(refresh!)` | Re-render on the next loop turn. For code changes; state changes do it themselves. |
| `(close!)` | Close the window and let `run` return. Safe from any thread. |
| `current` | Atom, while a window is up: `{:window ptr :tree node :thread id :stop! f :error e}`. nil otherwise. A tree node is `{:tag :props :widget :children}`; its classes, from `:class` and the tag alike, are under `:props :class` as a vector. |
| `last-error` | Atom: the last render or handler failure, `nil` after a good render. |
| `(load-css! css)` / `(load-css! css priority)` | Installs a `GtkCssProvider` for the display, so `:class` means something. Needs a display. For an app's own stylesheet, pass `:css` to `run` instead: it is installed before the first render, so no widget is ever measured unstyled. |
| `(register-widget! tag spec)` | Adds or replaces a widget tag. |
| `(each-frame-until! widget f)` | Calls `(f)` once per frame the widget draws, on the GTK thread and before that frame's layout, until it returns truthy. For a spec whose GTK call is dropped while the widget has no size yet -- `:carousel` sets its starting page this way. It keeps frames coming while it waits, so `f` must become true soon. A throwing `f` is reported and stops it. |
| `(register-signal! prop signal invoke)` / `(register-signal! prop signal invoke opts)` | Adds an `:on-*` prop. `invoke` is `(fn [user-fn widget raw-args])` and decides what the handler receives. `opts` carries what the callback's C signature has to be: `:argtypes` (default the two pointers every signal starts with) and `:rettype` (default `:void`) -- signals that pass more than an instance and an event, the `change-value` of a range say, need them, because a closure reads its arguments by position and cannot guess them. |
| `widgets` `signals` | The tables themselves, as atoms. |
| `default-window` | `{:ctor :set-content}` for a plain `GtkWindow`. |
| `chromeless-window` | The same, undecorated, for an app that draws its own top edge. |
| `(widget x)` | The GTK widget behind a lifecycle hook's event, a renderer element, or a node of `(:tree @current)`. |
| `(set-dispatch! f)` | Where data handlers and data lifecycle hooks go. `nil` turns it off. |
| `(later! f)` | Runs f on the GTK thread, soon. Safe from any thread. |
| `(on-gtk-thread! f)` / `(on-gtk-thread! f ms)` | Same, but waits for the value and rethrows. Runs inline if already there. |
| `(on-gtk-thread?)` | Whether the caller is on the loop's thread. |

## gtkiccup.alias

Components as **data**: a namespaced keyword plus a registered expansion fn,
emitted by the page as `[:ns/tag {attrs}]` and expanded by Replicant *lazily*
inside `reconcile` -- only when the attrs change. The attrs map is the cache key
and the expansion's whole input, so it must be plain `=`-comparable data (no
fresh closures; use data handlers). See [internals](internals.md#rendering) for
the data-flow contract. Supersedes the removed `gtkiccup.hiccup/memoized`.

| | |
| --- | --- |
| `(register-alias! k f)` / `(register-alias! k opts f)` | Registers `f` as the expansion for namespaced keyword `k`. `f` is `(fn [attrs children] -> hiccup-or-string)`; `attrs` is the emitted attrs map as plain data (plus `:replicant/alias-data`), `children` a flattened seq of any emitted children. `opts` may carry `{:purity :deps}` (advisory). Re-registering swaps the fn; cached nodes key on the keyword, so hot reload picks new bodies up. |
| `(registered? k)` | Whether `k` is a registered alias. `normalize` uses it to decide pass-through vs unknown-widget error. |
| `(clear-aliases!)` | Empties the registry, options and stats. |
| `(alias-stats)` | `{keyword {:expansions n :registered b}}` -- per-alias cold-expansion counters, so a cache that never fires is observable. |
| `(reset-stats!)` | Zeroes the expansion counters without touching the registry. |

The expansion result runs through `normalize` + `->replicant` at expansion time
(so signals, value boxing, data handlers and lifecycle hooks all still apply),
and a throwing expansion is logged via `on-alias-exception` rather than
corrupting the tree.

## gtkiccup.ratom

| | |
| --- | --- |
| `(atom init)` | Like `clojure.core/atom`, but a change marks the UI dirty. |
| `(invalidate!)` | Mark dirty by hand. `gtkiccup.core/refresh!` is this. |
| `(set-invalidate! f)` | Wiring, called by `run`. You should not need it. |

## gtkiccup.refs

Named widget references. Some GTK APIs connect two widgets --
`AdwViewSwitcher.set_stack`, a popover's parent, a size group -- and a hiccup
tree has no way to point one node at another. A `:ref` prop names a widget, so
another node can find it by name; the renderer registers the name when it
builds the widget and drops it when the element is released, so the table only
ever holds live widgets (and `close!` clears it). Names are strings; a keyword
is accepted and folded to one.

| | |
| --- | --- |
| `(register! name widget)` | Points `name` at `widget`. Returns the widget, so it composes into a spec. |
| `(unregister! name widget)` | Drops `name`, but only while it still points at `widget`: if a newer registration took the name over, that one is the truth and stays. |
| `(resolve name)` | The widget `name` refers to, or nil. Anything that is not a name is taken to be a widget already, so props like `:stack` also accept a kept pointer. |
| `(clear!)` | Forgets everything. `close!` calls it, and tests between cases. |
| `(names)` | The live ref names, for debugging. |
| `(render-done!)` | End-of-render hook, called by `reconcile`: warns (once per name) about `:ref` collisions that survived the render -- two widgets live under one name. |

A spec that resolves a ref while it is being constructed may find its sibling
is not built yet -- construction order follows the tree. That is why
`:view-switcher` resolves in both `:apply` and `:after-children`.

Uniqueness is the caller's job. A name points at ONE widget and `register!`
is last-write-wins, so two live widgets sharing a name silently lose one of
them: `resolve` only ever finds the widget that registered last, and the
earlier one never receives anything done through the name -- an image that
stays a placeholder ("why is my image not rendering!?"), a value that never
lands. The renderer DETECTS this and logs a warning once per name at the end
of the render (rebuild handovers -- where the displaced widget is released in
the same render -- are cancelled and stay silent), but the warning names the
problem; it does not fix it. The classic way to hit it is deriving the name
from item data and rendering the same item twice (one album in two strips):
deterministic names collide, and a rebuild does not heal it. Include what
makes the occurrence unique in the name (the section it lives in; an index
when duplicates are possible within one scope), or pass a kept pointer
instead of a name.

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
| `(application-window app-id)` / `(application-window)` | Pass to `run`'s `:window`: an `AdwApplicationWindow` under an `AdwApplication`. Registered non-unique, so a stuck instance cannot claim the D-Bus name; activated -- startup is emitted, which is what windows need before they can be added -- but never `g_application_run`: gtkiccup keeps pumping its own loop. `app-id` may be nil. The map carries `:app`, the app object as a lazy deref, for tests or callers wanting the pointer. |
| `(toast! overlay msg)` / `(toast! overlay msg seconds)` | Posts a toast into a `:toast-overlay`. |
| `specs` | The tag -> spec map, registered on load. |
| `initialized` | `{:version "<major>.<minor>" :widgets [...]}`, the version read back out of the loaded library -- here, not in the repo, it happens to be 1.9. Requiring the namespace runs `adw_init`. |
