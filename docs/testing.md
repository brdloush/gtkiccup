# Tests

`bb test` runs all of them, against real GTK. Most read their results back out
of real widgets with GTK getters rather than trusting the reconciler's own tree;
`reconcile_test` and most of `text_children_test` are the exceptions, since they
are checking pointer identity and hiccup normalization respectively.

`bb test` runs inside a persistent [cage](https://github.com/cage-kiosk/cage)
compositor (`scripts/cage-server`), so its windows never map on your desktop
and never steal focus. The first run starts cage if it is not up; `bb cage`
starts it by hand, and its one window can then go to a spare workspace.
`GTKICCUP_NO_CAGE=1 bb test` runs on the desktop instead. Only `bb test` does
this: the examples, `bb shot` and `bb dev` open their windows on the desktop,
and so does a single file run as `bb test/keyed_test.clj`.

| file | what it pins down |
| --- | --- |
| `reconcile_test.clj` | a patched widget keeps its pointer; children grow, shrink; a changed tag replaces |
| `keyed_test.clj` | keyed reorders and inserts move the same widgets; a removed widget is really finalized; a tag change keeps its position, also in a one-child container; a container with no positional insert still ends in the right order; hooks run once, with the widget already parented; data handlers dispatch, and are refused with nothing to dispatch to; classes in the tag; the tree snapshot carries classes under `:class` |
| `signals_test.clj` | clicks fire; a handler re-supplied by a later render replaces the stale one; and after 40 widget swaps the callback arenas come back -- the leak `connect!`'s `free-notify` was written for; an entry's `:on-change` fires from real typing alone, and `:on-activate` hands over the same text |
| `props_test.clj` | an explicit nil differs from an absent key; removal restores GTK defaults; css classes come off; a handler gained after creation connects |
| `text_children_test.clj` | text children fold into the text prop; the four refusals |
| `error_recovery_test.clj` | a bad view and a throwing handler leave the loop alive, and it recovers |
| `repl_reload_test.clj` | redefine a view, `refresh!`, see it on screen |
| `dev_test.clj` | all four `gtkiccup.dev` mechanisms against a live window |
| `extension_test.clj` | the extension points: registration, child props on `:append`, a pluggable window, `load-css!`, `:on-ready` firing exactly once, `:app-id`, `g_setenv` round-tripping, and `:on-layout` seeing a real height on the first frame (where `:on-render` reads zero) and not running in a loop while nothing paints -- the window still gets 0-15 frames a second here from the desktop around it, so the bound rules out a repaint loop rather than counting to zero, and `:css` styling a label that the first `:on-render` already measured |
| `adw_test.clj` | every Adw spec builds the GObject type it claims; each slot lands under the right parent; props reach real Adw setters and stay reactive; a `[:row "text"]` normalizes to its title; `adw/toast!` posts a toast; `:status-page` takes its title, description and icon at build, not only on a change; `:spinner` is an `AdwSpinner` |
| `splice_test.clj` | vector runs splice as children: the `(mapv ...)` shape, computed versus literal, nested runs, empty and nil runs dropped cleanly |
| `center_box_test.clj` | the slots fill at build; removing a slotted child clears only the slot it was really in; a stable re-render; keyed children keep their slots across a reorder |
| `leaf_widgets_test.clj` | separator orientation, aspect-frame ratio and child, scale bounds with the value written only when it differs, the menu-button look, child-on-a-button -- at build time and after a re-render |
| `label_props_test.clj` | every shape prop arrives where GTK reads it; the defaults are GTK's own; the props are reactive; removed props fall back |
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
| `motion_test.clj` | changing `:page` really animates rather than jumping, `:animate false` jumps, an unrelated re-render does not re-animate, a `:revealer` really opens and closes, and `nth-child` stops at the end of the sibling chain |
| `deckmd_test.clj` | markdown to slides, Pango escaping (including an injection attempt), and every key-to-action transition |
| `desktop_test.clj` | the `.desktop` entry has every key a compositor needs, install/uninstall/re-install are exact and reversible, and a missing `icons/` fails loudly rather than writing half an install. Writes into a temp `XDG_DATA_HOME`, never the real one |
