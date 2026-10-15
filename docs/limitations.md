# Limitations

Young. Missing: more widgets, more than one window from a single `run`, a
proper `GtkApplication` lifecycle, and a tagged release. The examples do not
use keys yet -- the monitor's rows are still ranks rather than identities, which
is what keys now make possible.

The window limitation is narrower than it sounds, and worth stating precisely:
`run` owns exactly one window (`current` is a single atom, and closing that
window ends the loop), but `(adw/application-window id)` does hand you a real
application window that another call can render into -- `test/application_test.clj`
builds two of them under one app. What is still missing is the lifecycle part:
gtkiccup keeps pumping its own main loop rather than letting
`g_application_run` own it, so the app is registered and activated but not
driven the way a packaged GNOME app would be.

Events are not checked against the widget. `[:label {:on-click f}]` connects
`clicked` to a `GtkLabel`, which has no such signal: GLib prints a `CRITICAL`
to stderr, the handler never fires, and nothing else breaks. The per-widget
table in [widgets.md](widgets.md) is the only thing telling you which events
are real.

No error is shown *in* the window -- it goes to stderr, so with no terminal in
view a broken render looks like nothing happened.

`gtkiccup.dev` is dev-only and unpolished too: `watch-files!` polls rather than using
inotify, and there is no `require`-graph awareness, so reloading a file does not
reload the files that depend on it.

Window size is a suggestion. Under a tiling window manager (PaperWM here)
`:height` is simply ignored -- `gtk_window_get_default_size` reports the tiled
value back. Nothing to fix on our side.

A few documented behaviours are GTK's, not ours, and are not going to change:

- Scrollbar policy is a GObject property with no getter, and both scrollbars
  report `get_visible` true under `:never`, `:always`, `:automatic` and
  `:external` alike. So `:hscroll`/`:vscroll` (and their `:h`/`:v` twins on
  `:scroll`) are documented rather than asserted: `test/scrolled_test.clj`
  checks that every documented shape builds, and nothing can check what the
  policy *value* is.
- Core `:clamp` is a centred box with `:max` turned into a size request, which
  means a page with a clamp cannot be dragged narrower than `:max`. That is
  plain GTK4 having no maximum-width layout; `gtkiccup.adw`'s `AdwClamp` is what
  fixes it. It is also applied at construction only, so raising `:max` on a
  re-render does nothing until the widget is rebuilt.
- Nothing in this repo measures frame rate any more. The renderer table in
  [examples.md](examples.md) keeps its fps column as a dated 2025 measurement,
  and its memory column is the part a command reproduces.
