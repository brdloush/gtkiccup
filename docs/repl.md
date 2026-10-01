# REPL workflow and dev helpers

You can keep the window open and reshape it while it runs. Two kinds of change,
and they behave differently.

**State changes are automatic.** That is the whole point of the reactive atom:
its watch marks the tree dirty, the loop notices, and only the props that
actually changed get pushed into GTK.

**Code changes are not, on their own.** Redefining a function touches no atom,
so nothing marks the tree dirty and the window keeps showing the old render,
even though the new code is already loaded.

Turn on one of the [dev helpers](#dev-helpers) and that goes away -- they watch
the vars, or just re-render on a timer. The rest of this section is what happens
*without* them, which is worth knowing because it explains what those helpers
are actually doing.

You do *not* need `#'home` for this. A plain call like `(home state)` resolves
through the var at call time in babashka, exactly as on the JVM, so a redef is
picked up on the next render. All that is missing is the render.

So a redef sits pending until *something* re-renders. Type into an entry, tick a
checkbox, click a button -- the resulting `swap!` marks the tree dirty, and the
very next render already uses your new code. `ui/refresh!` is just "re-render
now, without touching the UI".

## Setting it up

Structure the app so the view is a plain fn of state:

```clojure
(defn home [state]
  [:vbox {:spacing 10 :margin 16}
   [:label "items: " (count (:items @state))]
   ...])

(defn app []
  (let [state (r/atom {:draft "" :items []})]
    (fn [] (home state))))
```

`run` blocks, so start it on its own thread and keep the handle:

```clojure
(def app-thread (future (ui/run (app) :title "todo" :width 420 :height 320)))
```

Now edit `home`, re-evaluate just that form, and:

```clojure
(ui/refresh!)
```

The window repaints in place. Widgets are patched, not rebuilt, so focus and
the caret in a half-typed entry survive.

That `refresh!` is the step the [dev helpers](#dev-helpers) remove. One
`(dev/auto-refresh!)` at the start of a session and you never type it again.

## Inspecting and stopping

```clojure
(:window @ui/current)                 ; the live GtkWindow pointer
(-> @ui/current :tree :children)      ; the reconciled tree

(ui/close!)                           ; shut the window, let the loop return
```

`ui/close!` is the clean way out: it asks the loop to stop, and the window is
destroyed by the loop itself on the thread GTK expects. `future-cancel` also
tears the window down now -- it interrupts the loop, and `run`'s `finally` still
runs -- but it stops at an arbitrary point, so prefer `close!`. Closing the
window with the mouse ends the loop too.

## Threads

Every GTK call happens on the thread that ran `gtk_init` -- the future's thread
-- which is what GTK requires, and `(:thread @ui/current)` records which one it
is.

`refresh!`, `close!` and `swap!` are safe from any thread: they set a flag and
call `g_main_context_wakeup`, both of which are safe off-thread. The render and
the teardown happen back on the GTK thread.

Anything else that touches widgets must get over there itself. `dev/later!`
does that via `g_idle_add`, and `dev/on-gtk-thread!` waits for the result:

```clojure
(future
  (let [rows (slow-query)]                    ; off-thread is fine
    (dev/later! #(reset! state rows))))       ; widgets only over there
```

Calling a widget function from the wrong thread does not raise -- it segfaults.
`dev/screenshot!` marshals itself for exactly this reason.

## Gotchas

- Calling a fn never captures it, so redefining works. Passing it as a **value**
  does capture: `(def v home)` or `(map home xs)` freeze the fn as it is now.
- Re-evaluating `(ui/run (app) ...)` calls `(app)` again, which builds a **fresh**
  `r/atom`, so your state resets. To keep state across restarts, move it to a
  top-level `defonce`.
- One window at a time. `ui/current` is a single atom, so `refresh!` and `close!`
  act on the most recently started window.
- **A NULL pointer from C is not `nil`.** It arrives as a live MemorySegment at
  address 0, so `nil?` and `some?` both lie about it. Use `gtkiccup.ffi/null?`.
  Walking a sibling chain with `nil?` runs off the end and GTK starts printing
  CRITICALs.
- `(ui/run view ...)` captures the fn value. Pass `#'view` if you intend to
  redefine it and have the running window notice.
- **A size request is a minimum, not a size.** `:width`/`:height` set a floor.
  For a fixed-size image use `:icon` with `:size` (a real pixel size); a
  `:picture` holding a 512px texture asks for 512px and gets it.
- **`GdkTexture` rasterises an SVG at its intrinsic `width`/`height`**, not at
  the size you draw it. A 128px raster scaled down to a 38pt logo looks soft, so
  set the intrinsic size large and keep the `viewBox` small.
- **Keep `<svg>` near the top of the file.** GdkPixbuf sniffs the first bytes to
  choose a loader, so a long header comment before the element gets the whole
  file rejected as "Unrecognized image file format".

`test/repl_reload_test.clj` drives this whole loop end to end -- redefines a view,
calls `refresh!`, then reads the text back out of the real `GtkLabel`:

```
1) initial: old 7
2) after swap!, no refresh needed: old 8
3) after redef, before refresh! (stale, as expected): old 8
4) after ui/refresh!: NEW 8 123
```

## Dev helpers

`gtkiccup.dev` removes the manual `refresh!`. Four mechanisms; pick one, or compose
them. Nothing here is needed at runtime.

| | what it catches | you must | cost |
| --- | --- | --- | --- |
| `(dev/auto-refresh!)` | everything | keep views pure | ~0.3% of a core |
| `(dev/watch-ns! 'todo)` | fns already interned when it ran | re-run it after adding a fn | none |
| `(dev/defview home [st] ...)` | that one var | use `defview` instead of `defn` | none |
| `(dev/watch-files! "src" "examples")` | anything saved to disk | `defonce` for state | one stat per file, every 300ms |

```clojure
(require '[gtkiccup.dev :as dev])

(dev/auto-refresh!)        ; re-render on a timer. no registration at all
(dev/watch-ns! 'todo)      ; event-driven, no wasted renders
(dev/watch-files! "src")   ; edit and save, no REPL attached

(dev/status)               ; what is running
(dev/stop!)                ; stop all of it (leaves the window up)
```

### auto-refresh!

Re-renders on a timer whether anything changed or not. A no-op re-render of ~90
widgets measures **0.28 ms**, so at the default 100 ms interval this is well
under 1% of a core, and the dirty flag was never buying much.

Nothing to register and nothing to remember. It catches a redefined view, a
redefined nested component, a whole namespace reload, a changed top-level value.

The one rule: **views must be pure.** They now run 10 times a second, so a
`println` or a `swap!` inside one fires continuously.

### watch-ns! and defview

Babashka vars support `add-watch`, and a plain `(defn home ...)` redef fires it --
repeatedly, because the watch survives the redef. So these are event-driven: no
polling and no wasted renders.

`watch-ns!` arms every fn currently interned in a namespace. A fn you define
*afterwards* is not covered, since there was nothing to watch at the time. Either
re-run it, or declare views with `defview`, which arms itself:

```clojure
(dev/defview home [state]
  [:vbox {} [:label "items: " (count (:items @state))]])
```

The ordering works out: a redef fires the watch armed by the previous definition,
then re-arms under the same key. So the first eval arms it, and every later eval
both refreshes and re-arms. Forget `defview` on one view and that view silently
stops live-updating -- which is the trade against `auto-refresh!`.

### watch-files!

Polls `.clj` mtimes, `load-file`s what changed, then re-renders. The only option
that needs no REPL: save in your editor and the window updates. A syntax error in
a half-saved file is reported and the watcher keeps going.

`load-file` re-runs the file's top-level forms, so a top-level `(def state
(r/atom ...))` is rebuilt on every save. Use `defonce`, or keep state inside the
fn `run` closes over -- which is what the todo example does, so its items survive
a save. Keep `ui/run` out of the top level or a save opens a second window.

`test/dev_test.clj` drives all four against a live window and reads the results
out of the real `GtkLabel`.
