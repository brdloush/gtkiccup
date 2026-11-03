# Tests

`bb test` runs all of them, against real GTK. Most read their results back out
of real widgets with GTK getters rather than trusting the reconciler's own tree;
`reconcile_test` and most of `text_children_test` are the exceptions, since they
are checking pointer identity and hiccup normalization respectively.

`bb test` runs every file inside its own private, windowless
[cage](https://github.com/cage-kiosk/cage) compositor (`scripts/headless`).
cage uses the wlroots *headless* backend: it needs no DRM master and maps no
host window, so test windows never appear on your desktop and never steal
focus. Each run boots a fresh compositor (about 30 ms) and tears it down at
the end, so there is no server to start, stop or clean up. The whole suite
takes about 30 s.

```bash
bb test                  # the whole suite, in a private headless cage
bb tests-direct          # the same files, on the display you are on now
bb test/keyed_test.clj   # one file, on your display
scripts/headless bb test/keyed_test.clj   # one file, headless
```

Both tasks run the same files. A failing file does not stop the run: the
runner collects the failures and ends with either `SUITE OK files=34` or
`SUITE FAILED n of 34 -- [...]` and exit 1. `bb.edn`'s `tests-direct` task
holds the file list; `test` is that task wrapped in `scripts/headless`. A new
test file that is not in that list silently never runs.

`scripts/headless` owns its environment: it clears `DISPLAY` and sets
`WLR_BACKENDS=headless`, `WLR_HEADLESS_OUTPUTS=1` and
`WLR_LIBINPUT_NO_DEVICES=1` before it runs `cage -- <command>`. Setting those
variables without `cage --` does nothing: the child then inherits the shell's
`WAYLAND_DISPLAY` and runs on your desktop. The proof that a run really was
headless is cage's log line `Creating headless backend`. cage passes the
child's exit status through, so a failing suite fails the task. Two variables
change it: `GTKICCUP_CAGE_BACKENDS=wayland` nests cage under your compositor
instead (one host window appears), and `GTKICCUP_CAGE_OUTPUTS` sets the number
of virtual outputs. The INFO line `unable to lock lockfile .../wayland-0.lock`
at startup is wlroots probing, not an error.

The headless output has scale 1, so a test that depends on the display scale
sees 1 under `bb test`. cage is a kiosk: it sizes every window to the output,
1280x720 unless something changes it, whatever size the window asked for.

`bb shot` uses the same wrapper. Before it opens the app, `examples/shot.clj`
sets the output to the target's `:size` times 2, at scale 2, through
`scripts/wl_output.clj` -- a small client for the wlroots output-management
protocol, the one `wlr-randr` speaks. The kiosk then gives the window exactly
`:size`, rendered at HiDPI. `GTKICCUP_SHOT_SCALE` changes the factor. The
weather shot reads an empty config in a temp dir, never your
`~/.config/bb-weather.edn`, so its language comes from `LANG` and its place is
the default (Prague). Two
differences from a desktop shot: the window is maximized, so the header bar's
maximize button shows its "restore" glyph, and content taller than `:size`
scrolls instead of growing the window. `bb shot-direct` takes the picture on
your own display, at its scale. The examples and `bb dev` always open their
windows on the desktop: those windows are meant to be seen.

| file | what it pins down |
| --- | --- |
| `reconcile_test.clj` | a patched widget keeps its pointer; children grow, shrink; a changed tag replaces |
| `alias_test.clj` | a late-bound alias expands once for equal attrs and again only for the rows whose attrs changed; `alias-stats` counts it; nested aliases stay lazy; hooks fire once and not on a cache hit; an unregistered alias keyword fails at emit time; `:on-alias-exception` logs and the frame survives; `:replicant/alias-data` reaches the attrs |
| `keyed_test.clj` | keyed reorders and inserts move the same widgets; a removed widget is really finalized; a tag change keeps its position, also in a one-child container; a container with no positional insert still ends in the right order; hooks run once, with the widget already parented; data handlers dispatch, and are refused with nothing to dispatch to; classes in the tag; the tree snapshot carries classes under `:class` |
| `signals_test.clj` | clicks fire; a handler re-supplied by a later render replaces the stale one; and after 40 widget swaps the callback arenas come back -- the leak `connect!`'s `free-notify` was written for; an entry's `:on-change` fires from real typing alone, and `:on-activate` hands over the same text |
| `props_test.clj` | an explicit nil differs from an absent key; removal restores GTK defaults; css classes come off; a handler gained after creation connects |
| `memo_test.clj` | a data handler resolves to the same fn across renders for the same `[prop data]`, a different fn for different data, and still dispatches at click time; an fn handler passes through; a plain fn component re-runs every render. No window |
| `memo_budget_test.clj` | with a tiny byte budget the data-handler cache stays bounded while distinct handlers churn; handlers used every render keep the same fn; a cold handler is really evicted. No window |
| `text_children_test.clj` | text children fold into the text prop; the four refusals |
| `error_recovery_test.clj` | a bad view and a throwing handler leave the loop alive, and it recovers |
| `repl_reload_test.clj` | redefine a view, `refresh!`, see it on screen |
| `dev_test.clj` | all four `gtkiccup.dev` mechanisms against a live window |
| `extension_test.clj` | the extension points: registration, child props on `:append`, a pluggable window, `load-css!`, `:on-ready` firing exactly once, `:app-id`, `g_setenv` round-tripping, and `:on-layout` seeing a real height on the first frame (where `:on-render` reads zero) and not running in a loop while nothing paints -- the window still gets 0-15 frames a second here from the desktop around it, so the bound rules out a repaint loop rather than counting to zero, and `:css` styling a label that the first `:on-render` already measured |
| `adw_test.clj` | every Adw spec builds the GObject type it claims; each slot lands under the right parent; props reach real Adw setters and stay reactive; a `[:row "text"]` normalizes to its title; `adw/toast!` posts a toast; `:status-page` takes its title, description and icon at build, not only on a change; `:spinner` is an `AdwSpinner` |
| `splice_test.clj` | vector runs splice as children: the `(mapv ...)` shape, computed versus literal, nested runs, empty and nil runs dropped cleanly |
| `center_box_test.clj` | the slots fill at build; removing a slotted child clears only the slot it was really in; a stable re-render; keyed children keep their slots across a reorder |
| `leaf_widgets_test.clj` | separator orientation, aspect-frame ratio and child, scale bounds with the value written only when it differs, the menu-button look, child-on-a-button -- at build time and after a re-render; a `:text-view` is read-only, wraps by word, keeps `vexpand` off, and patches its text, wrap mode and justification in place |
| `label_props_test.clj` | every shape prop arrives where GTK reads it, `:wrap-mode` and `:natural-wrap` included, with the Pango and GTK enum numbers; the defaults are GTK's own; the props are reactive; removed props fall back |
| `picture_props_test.clj` | `:file` loads a texture; the defaults are the useful ones; `:content-fit` is reactive; `:can-shrink false` sets a floor |
| `refs_test.clj` | `:ref` registers the widget and resolves by name or keyword; moving it points the name at the new widget; an unknown or dropped name is nil, not dangling; two live widgets sharing a name warn exactly ONCE, while a rebuild handing the name to a fresh widget stays silent |
| `application_test.clj` | the application-window ctor builds a real application window; the app carries the id; content is exactly one widget; the title behaves like any window's; two windows under one app; `run`'s reconcile path renders into it; and a presented one is both realized and mapped |
| `scrolled_test.clj` | `:scrolled` is the real `GtkScrolledWindow` with its child parented and its adjustment present; all six documented policy/wheel shapes build; `install-wheel-scroll!` returns a controller and survives a second call. The policy *values* themselves are GObject properties, not widget state, so they stay documented rather than asserted |
| `overlay_split_view_test.clj` | both slots fill; the width props (fraction, min, max) land where GTK reads them and stay reactive; swapping slots keeps one widget per slot |
| `view_stack_test.clj` | pages are added by `:name` and titled by `:title` / `:icon`; the starting page is picked once the children are in; switching by name works; a removed page leaves the stack; `:on-switch` reports the page by name -- for the page chosen at build as well as for a switch |
| `clamp_test.clj` | core `:clamp` is a centred box and turns `:max` into a width request, `:min` is read by nobody, and a changed `:max` on an existing clamp is not re-applied; loading `gtkiccup.adw` swaps in the real `AdwClamp` with its documented 600 default |
| `view_switcher_test.clj` | bound to the stack by its `:ref` name, resolved even when the stack was built later; `:policy` reactive; dropping `:stack` unbinds |
| `sysinfo_test.clj` | the `/proc` readings, formatting, and a machine with no swap |
| `screenshot_test.clj` | a PNG really is written, at the display's scale rather than the logical size; a single widget too; `later!` runs on the GTK thread, and `screenshot!` marshals itself there |
| `openmeteo_test.clj` | all 28 WMO codes map to a label, icon and sky, day and night; formatting; the view model; the staleness banner's thresholds |
| `weather_test.clj` | config defaults, round-trip and recovery from a corrupt file; the offline path builds a whole tree from cached data and the real temperature appears on screen |
| `i18n_test.clj` | the locale is read from the environment and survives nonsense; the Czech dictionary covers every English key; a missing string falls back to English; one response renders in both languages with the numbers and icons untouched; Czech counts in six shapes (nil, `45 minut`, `3 hodiny`, `8 hodin`, `3 dny`,
`9 dní`); the header switch rebuilds from the cache and is written to disk |
| `input_test.clj` | keys arrive as names with modifiers; the return value decides whether the event stops; a throwing handler is contained and reports "not handled"; ordinary signals still work |
| `motion_test.clj` | changing `:page` really animates rather than jumping, `:animate false` jumps, an unrelated re-render does not re-animate, a `:revealer` really opens and closes, `nth-child` stops at the end of the sibling chain, and a carousel built with a starting `:page` is on that page once the window has a size |
| `deckmd_test.clj` | markdown to slides, Pango escaping (including an injection attempt), and every key-to-action transition |
| `desktop_test.clj` | the `.desktop` entry has every key a compositor needs, install/uninstall/re-install are exact and reversible, and a missing `icons/` fails loudly rather than writing half an install. Writes into a temp `XDG_DATA_HOME`, never the real one |
