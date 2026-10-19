# How it works

Nine namespaces. Six do the work -- `ffi`, `ratom`, `widgets`, `hiccup`,
`renderer`, `core` -- `refs` keeps the widget names a hiccup tree cannot carry,
and two are optional: `dev` (development conveniences, never required by a
running app) and `adw` (libadwaita, which core does not know exists).

| File | Lines | Job |
| --- | --- | --- |
| `src/gtkiccup/ffi.clj` | 386 | `defcfn` bindings for the GTK4/GObject symbols the library uses |
| `src/gtkiccup/ratom.clj` | 25 | reactive atom: a normal atom whose watch marks the UI dirty |
| `src/gtkiccup/widgets.clj` | 649 | the tag and signal tables, and the props every widget takes |
| `src/gtkiccup/hiccup.clj` | 203 | validation, text children, splicing, and the translation into Replicant's hiccup |
| `src/gtkiccup/renderer.clj` | 383 | Replicant's `IRender` protocol, for GTK |
| `src/gtkiccup/core.clj` | 327 | the window, the main loop, the GTK thread, the public API |
| `src/gtkiccup/refs.clj` | 68 | named widget references, kept live by the renderer |
| `src/gtkiccup/dev.clj` | 260 | dev-only: var watches, auto-refresh, file watching, screenshots |
| `src/gtkiccup/adw.clj` | 642 | optional: libadwaita bindings and 22 tags, 20 of them new -- its `:bin` and `:clamp` replace the core tags of the same name, for 36 tags in total. Core does not know it exists |

An app's own strings are its own business, so translation is not in `src/`.
`examples/i18n.clj` is the whole mechanism -- a dictionary per language, `t`,
and a root binding for the language in force -- and `examples/weather_i18n.clj`
is what the weather app says in English and Czech.

## Rendering

`ui/run` opens a `GtkWindow` and calls your render fn. The hiccup it returns
goes through two pure passes in `gtkiccup.hiccup`: `normalize` validates the
whole tree and folds text children into props, `->replicant` translates it --
and then [Replicant](https://github.com/cjohansen/replicant) diffs it against
the previous render. A child position also accepts a *run* of siblings:
`spliced` flattens lazy seqs and vectors-whose-head-is-a-vector, so `(mapv
cover-card cards)`, `(mapcat ...)` output and nested runs of them splice like
a seq would -- a vector of children is not a widget form.
`test/splice_test.clj` covers it.

A render re-runs every function component, even one nothing has changed.
`gtkiccup.hiccup/memoized` opts a view fn out of that: `normalize` caches the
expansion of `[fn args]` and reuses it -- including the subtree's prebuilt
`->replicant` form -- while the args are `=`. Two rules keep this honest. The
fn must be **pure in its args** (same args, same tree: no derefing app state,
no clock, no rand -- pass everything it renders through args), and expansions
containing lifecycle hooks are never cached, because a hook wrapper closes
over render-scoped state. Eviction is a whole-cache clear at 4000 entries:
views are a handful of shapes, so a miss after a clear is one expansion each.
Handlers written as data (`{:on-click [:thing/do 3]}`) are memoized by
`[prop data]` too, so two renders produce identical handler values and the
diff sees no churn. `test/memo_test.clj` asserts both the wins and the rules.

Replicant talks to its host only through one protocol, `IRender`: create an
element, set an attribute, insert a child before another, remove one.
`gtkiccup.renderer` is that protocol for GTK, extended through metadata, so
there is no `deftype`. Replicant is pure `.cljc` with no dependencies, and runs
under babashka unchanged.

When a reactive atom changes, the tree is marked dirty. On the next main-loop
turn the render fn runs again and only properties that actually changed are
pushed into GTK. `test/reconcile_test.clj` asserts the label pointer stays
identical across a state change.

GTK is not the DOM, and four differences shaped the renderer:

- **Widgets are made late.** Replicant creates an element and then sets its
  attributes one at a time, but a GTK constructor wants its props up front: a
  box needs its spacing, an image its file. So a widget is constructed on first
  use, by which time every attribute is known.
- **Widgets are owned.** A GTK container frees a widget it removes, and a keyed
  move is a remove plus an insert. So every widget is ref-sunk when it is made
  and unref'd only when Replicant really removes it. `keyed_test` proves the
  freeing with a weak reference.
- **The bulk of the work is done at the end.** Replicant's operations record
  rather than act: `set-attribute` and `remove-attribute` write into the
  element's `:props` and note the change, and the child-list operations edit a
  mirror. Once the diff is done, each touched widget gets all its changed props
  at once, then each touched container is brought in line, then lifecycle hooks
  run. Live does not work: on a tag change Replicant inserts the new child
  before removing the old one, which empties a GTK container that holds one
  child. And a spec's `:apply` reads its siblings -- a carousel's `:page` must
  see this render's `:animate`.

  Two operations do touch GTK during the diff, both because waiting gains
  nothing: `add-class`/`remove-class` call `widget-add/remove-css-class`
  straight away when the widget already exists, and the *first* time an event
  handler appears on an existing widget it connects then, rather than at sync.
  Neither breaks the rule that matters here -- a handler is connected once, and
  later changes just swap what the holder points at -- but the invariant is
  "props and structure deferred", not "nothing is called".
- **Slots are remembered.** Which slot a child sits in is a fact about GTK,
  not about its props: props are applied before the structure is synced, so on
  a re-render the element's `:slot` is already the *new* one when `:remove`
  runs. `gtk-append!` records the slot the child actually landed in, and
  `gtk-remove!` is handed that. A keyed reorder between slots would otherwise
  clear the wrong slot -- the child stays parented, and the new `set_*` call
  claims all is well. `center_box_test` and `overlay_split_view_test` cover it.

## Props that appear and disappear

Diffing props has two traps, both of which produced silently broken widgets.

**An explicit nil is not the same as an absent key.** `:sensitive (seq "")` is
`:sensitive nil`, and it must reach the widget. Replicant follows HTML here:
a false or nil attribute is *removed*, and a false one is never sent on the
first render. So every prop travels boxed in a one-element vector -- always
truthy, compared by value -- and the renderer unboxes it. `:sensitive false`
works as written.

**A removed prop must fall back to its GTK default, not to nil.** Dropping
`:sensitive` re-enables the widget; dropping `:margin` goes to 0; dropping
`:hexpand` goes to false. CSS classes are diffed as sets, so a class that leaves
the vector is actually removed.

## Signals and the stale-closure trap

An event handler closes over the state as it was *at the time of that render*.
If you connect it once and forget it, it goes stale. If you reconnect it on
every render, you leak connections.

So each widget gets one C callback, connected once, that reads the current
handler out of a holder atom. Re-renders just `reset!` the holder:

```clojure
(def free-notify
  (ffi/callback (ffi/global-arena) free-notify! [:pointer :pointer] :void))

(defn connect! [widget prop holder]
  (let [{:keys [signal invoke argtypes rettype controller attach]
         :or   {argtypes [:pointer :pointer] rettype :void}} (@signals prop)
        void?   (= :void rettype)
        target  (if controller (controller) widget)
        ;; "returned nothing" means not handled, which for an :int signal
        ;; (key-pressed) is 0 -- nil marshals as a real non-zero pointer
        ->ret   (fn [v] (cond void? nil (number? v) v v 1 :else 0))
        arena   (ffi/shared-arena)
        cb      (ffi/callback arena
                              (fn [& args]
                                ;; never let an exception cross back into C
                                (try
                                  (->ret (when-let [f @holder]
                                           (invoke f widget (vec args))))
                                  (catch Throwable t
                                    (report! (str prop " handler failed") t)
                                    (->ret nil))))
                              argtypes rettype)]
    ;; register before connecting, so a teardown racing the connect still finds
    ;; it; user_data = cb, so the destroy-notify locates this exact connection
    (swap! arena->cb assoc cb arena)
    (g/signal-connect-data target signal cb cb free-notify 0)
    ;; controller signals (the keyboard) need a capture-phase controller added
    ;; to the toplevel, not to the widget itself -- recorded for disconnect
    (when controller
      (g/event-controller-set-phase target (:capture g/phase))
      (g/widget-add-controller (if (= :window attach) (:window @current) widget)
                               target))
    target))
```

Each callback gets its own `ffi/shared-arena`, not the process-wide global one,
and `g_signal_connect_data` is handed `free-notify` as its destroy-notify. GTK
fires it when it drops the connection -- an explicit disconnect, or the finalize
behind `release!`'s unref -- with the `user_data`, which is the callback pointer
itself. It looks the arena up in `arena->cb` and closes it, freeing the
trampoline and the closure's captures: that is what stops connections piling up
across navigation churn, the leak that exhausted `-Xmx`. The `free-notify`
trampoline itself lives in `global-arena`, so it is valid for the whole process
and is never freed -- GTK may fire it long after any given connect, and the
callback stays valid until the notify lands, since GTK fires it only after it
has stopped calling back.

A widget can also *gain* a handler in a later render -- you add an `:on-click` to
a button that is already on screen. There was no holder for it, so nothing was
ever connected and that button stayed dead for as long as it lived. The fix is
in the diff, not in a sync pass: `rp/set-event-handler` makes the holder and,
if the widget is already built, connects it on the spot. Handlers attached at
creation connect in the same `connect!` call the widget's own props get, so
there is no second route through the main loop to keep in mind.

`test/signals_test.clj` covers the stale case: it clicks, re-renders, then clicks
a handler that captured the old value and checks it sees the new one.
`test/props_test.clj` covers the appearing, disappearing and nil cases.

## Main loop

Babashka has no GTK main loop to hand over to, so `run` drives it:

```clojure
(while @running
  (g/main-iteration nil 1)                       ; block until GTK has work
  (loop [i 0]                                    ; then drain what else queued
    (when (and (< i 64) (g/<-gbool (g/main-iteration nil 0)))
      (recur (inc i))))
  (when @dirty (render!)))
```

The interesting part is the blocking call. Because the loop has to check a dirty
flag that other threads set, the obvious design is to poll -- and it did, every
8ms, which cost **2.8% of a core doing nothing**. Now it blocks, and everything
that dirties the UI also calls `g_main_context_wakeup`:

```clojure
(r/set-invalidate! #(do (vreset! dirty true) (wake!)))
```

An idle window now measures **0.00%**. `close!` wakes the loop the same way, or
it would sit blocked forever waiting for an event that is not coming.

The second drain is bounded so a busy source cannot starve rendering.

## When a view is broken

A typo in a view -- a stray string, a misspelled widget -- used to freeze the
window. The exception escaped `render!`, escaped the main
loop, and killed the thread. Nothing pumped GTK after that, so the window sat
there unresponsive, and because it died inside a `future` the error was
swallowed: no message at all. `ui/close!` could not help either, since the loop
that destroys the window was gone.

Now a failed render is contained. The window keeps pumping, the last good render
stays on screen, and the error is printed:

```
[gtk] render failed: :vbox has no text of its own: ["oops"]
  put the text in a child, e.g. [:vbox {} [:label "oops"]]
       {:tag :vbox, :texts ["oops"]}
```

Fix the view, re-render, and it recovers. Nothing to restart.

Three things make that work:

- **`normalize` validates the whole tree before `reconcile` touches a widget**,
  so a malformed view is rejected without half-mutating the window.
- **Event handlers are wrapped**, so an exception in your `:on-click` is reported
  instead of crossing back into C, where behaviour is undefined.
- **Errors are deduplicated.** With `dev/auto-refresh!` running, a broken view
  would otherwise print ten times a second.

The last failure is kept in `gtkiccup.core/last-error` and on `(:error @ui/current)`,
and clears on the next good render. `test/error_recovery_test.clj` covers a bad
child, an unknown widget, recovery, and a throwing handler.

## Extension points

Core knows nothing about libadwaita. A handful of small hooks are what let an optional
namespace add 22 widget tags -- 20 new ones, since its `:bin` and `:clamp` swap out
the core tags -- along with window types, signals and a stylesheet, from outside.

| | what it is for |
| --- | --- |
| `widgets` / `signals` are atoms | `register-widget!` and `register-signal!` add tags and events from another namespace |
| signals carry `:argtypes` / `:rettype` | not every signal is `(instance, data) -> void`. A key handler is five arguments and returns whether it consumed the event |
| signals carry `:controller` / `:attach` | input arrives through a `GtkEventController`, which you connect to and then add to a widget -- `:attach :window` puts it on the toplevel, in the **capture** phase so a focused widget cannot swallow the key first |
| `run` takes `:css` | installed right after `gtk_init`, before the first render. CSS from `:on-ready` is too late for anything the first render measured: a GtkLabel asked for its Pango layout then keeps it, default font and all, until its text changes -- and in a chromeless window nothing restyles the window later to rescue it |
| `run` takes `:on-render` | called after *every* render, once the widgets carry this frame's props. Too early to measure anything on the first render: nothing is laid out yet |
| `run` takes `:on-layout` | called after every frame GTK paints, from the window frame clock's `after-paint` signal -- the first point where sizes and positions are real, the first frame included. Fires only when something paints, so an idle window does not spin: measured 0 to 15 calls in a quiet second here, against the ~60/s a self-feeding repaint loop would give. It is not a guarantee of *zero* extra frames -- a pointer crossing the window or the desktop repainting next door costs a frame or two -- so the honest statement is that nothing wakes it on a timer |
| `run` takes `:app-id` / `:app-name` | the process identity a compositor matches against a `.desktop` file -- set before `gtk_init`, because afterwards it does nothing |
| specs carry `:after-children` | for a container whose props refer to its children. A carousel's initial `:page` is ignored otherwise, because `:apply` runs before the children exist |
| `:ref` names are live | the renderer registers a widget's `:ref` name when it builds the widget and drops it on release, so another node can name a sibling instead of pointing at it -- `:view-switcher`'s `:stack` is resolved in `:apply` and again in `:after-children`, because the stack may be built late |
| `:append` / `:remove` get the child's props | lets a container read a `:slot` prop and put the child somewhere specific |
| specs may carry `:insert-after` | `(fn [parent child sibling-or-nil props])`: a positional insert, so a keyed move is one call. Without it, an insert in the middle re-appends the children that follow it, which is right but more work |
| `run`'s `:window` option | `{:ctor f :set-content f}`. `AdwWindow` has no titlebar of its own, which is what makes an Adw header bar sit flush |
| `run`'s `:on-ready` | `(f window root-node)`, once, after `gtk_init` and the first render. For installing CSS (needs a display) or keeping a pointer to a widget you just built |
| `load-css!` | installs a `GtkCssProvider` for the display, so `:class` attaches to something |
| `wake!` in the main loop | not an API, but what makes a blocking loop possible |

`test/extension_test.clj` covers all of them.
