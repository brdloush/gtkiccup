# Limitations

Young. Missing: more widgets, multiple windows, `GtkApplication`
integration, and a tagged release. The examples do not use keys yet -- the
monitor's rows are still ranks rather than identities, which is what keys now
make possible.

Events are not checked against the widget. `[:label {:on-click f}]` connects
`clicked` to a `GtkLabel`, which has no such signal: GLib prints a `CRITICAL`
to stderr, the handler never fires, and nothing else breaks. The per-widget
table above is the only thing telling you which events are real.

No error is shown *in* the window -- it goes to stderr, so with no terminal in
view a broken render looks like nothing happened.

`gtkiccup.dev` is dev-only and unpolished too: `watch-files!` polls rather than using
inotify, and there is no `require`-graph awareness, so reloading a file does not
reload the files that depend on it.

Window size is a suggestion. Under a tiling window manager (PaperWM here)
`:height` is simply ignored -- `gtk_window_get_default_size` reports the tiled
value back. Nothing to fix on our side.
