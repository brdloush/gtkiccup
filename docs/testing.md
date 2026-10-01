# Tests

`bb test` runs all of them, against real GTK. Most read their results back out
of real widgets with GTK getters rather than trusting the reconciler's own tree;
`reconcile_test` and most of `text_children_test` are the exceptions, since they
are checking pointer identity and hiccup normalization respectively.

| file | what it pins down |
| --- | --- |
| `reconcile_test.clj` | a patched widget keeps its pointer; children grow, shrink; a changed tag replaces |
| `keyed_test.clj` | keyed reorders and inserts move the same widgets; a removed widget is really finalized; a tag change keeps its position, also in a one-child container; a container with no positional insert still ends in the right order; hooks run once, with the widget already parented; data handlers dispatch, and are refused with nothing to dispatch to; classes in the tag; the tree snapshot carries classes under `:class` |
| `signals_test.clj` | clicks fire; a handler re-supplied by a later render replaces the stale one |
| `props_test.clj` | an explicit nil differs from an absent key; removal restores GTK defaults; css classes come off; a handler gained after creation connects |
| `text_children_test.clj` | text children fold into the text prop; the four refusals |
| `error_recovery_test.clj` | a bad view and a throwing handler leave the loop alive, and it recovers |
| `repl_reload_test.clj` | redefine a view, `refresh!`, see it on screen |
| `dev_test.clj` | all four `gtkiccup.dev` mechanisms against a live window |
| `extension_test.clj` | the extension points: registration, child props on `:append`, a pluggable window, `load-css!`, `:on-ready` firing exactly once, `:app-id`, and `:on-layout` seeing a real height on the first frame (where `:on-render` reads zero) and going quiet when nothing paints, and `:css` styling a label that the first `:on-render` already measured |
| `adw_test.clj` | every Adw spec builds the GObject type it claims; each slot lands under the right parent; props reach real Adw setters and stay reactive |
| `sysinfo_test.clj` | the `/proc` readings, formatting, and a machine with no swap |
| `screenshot_test.clj` | a PNG really is written, at the display's scale rather than the logical size; a single widget too; `later!` runs on the GTK thread, and `screenshot!` marshals itself there |
| `openmeteo_test.clj` | all 28 WMO codes map to a label, icon and sky, day and night; formatting; the view model; the staleness banner's thresholds |
| `weather_test.clj` | config defaults, round-trip and recovery from a corrupt file; the offline path builds a whole tree from cached data and the real temperature appears on screen |
| `i18n_test.clj` | the locale is read from the environment and survives nonsense; the Czech dictionary covers every English key; a missing string falls back to English; one response renders in both languages with the numbers and icons untouched; Czech counts in three shapes (`3 hodiny`, `8 hodin`); the header switch rebuilds from the cache and is written to disk |
| `input_test.clj` | keys arrive as names with modifiers; the return value decides whether the event stops; a throwing handler is contained and reports "not handled"; ordinary signals still work |
| `motion_test.clj` | changing `:page` really animates rather than jumping, `:animate false` jumps, an unrelated re-render does not re-animate, and `nth-child` stops at the end of the sibling chain |
| `deckmd_test.clj` | markdown to slides, Pango escaping (including an injection attempt), and every key-to-action transition |
| `desktop_test.clj` | the `.desktop` entry has every key a compositor needs, install/uninstall/re-install are exact and reversible, and a missing `icons/` fails loudly rather than writing half an install. Writes into a temp `XDG_DATA_HOME`, never the real one |
