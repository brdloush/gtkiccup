# Desktop integration

Out of the box every one of these windows is, as far as the desktop is
concerned, a program called **`bb`**: same generic icon, same name. Two separate
things are wrong there, and only one of them can be fixed at runtime.

## The name: runtime, no files

`run` takes `:app-id` and `:app-name`:

```clojure
(ui/run (app)
        :app-id "cz.brdloush.BbWeather"     ; becomes the Wayland app_id
        :app-name "Weather")
```

These have to be set before the first window exists, because GTK derives the
app_id from the process name when it creates the surface. `run` does it before
`gtk_init` for that reason -- setting it afterwards silently does nothing.

## The icon: needs a .desktop file

Wayland has no per-window icon message, so a compositor takes the app_id and
goes looking for a matching `.desktop` file. GTK 4.16 added support for the
newer `xdg-toplevel-icon` protocol, and on GTK 4.22 with GNOME Shell 50 it still
does not help: **GNOME's switcher is app-centric.** It shows the icon of the
application it matched the window to and ignores what the window asks for.

Tested in both directions on the same app_id and the same icon name:

| | switcher icon |
| --- | --- |
| `gtk_window_set_icon_name` only | generic |
| plus a matching `.desktop` file | the real icon |
| file removed again | generic |

So `gtk_window_set_icon_name` is not worth calling on GNOME. The file is the
mechanism.

```bash
bb install-desktop      # one .desktop file + one icon per app, in ~/.local/share
bb uninstall-desktop    # removes exactly what it wrote, nothing else
```

If an icon does not appear straight away, GNOME has not rescanned the
applications directory yet. It picks new entries up on its own, but not always
instantly -- and an app added to the list after a previous install needs
`bb install-desktop` run again.

Two details that matter:

- **`Path=`** is set to the project root, so a dock launch works from any
  working directory. Without it `bb weather` cannot find the tasks.
- **The icon name equals the app_id**, so the `.desktop` file, the icon file and
  the live window all agree on one string.

## The icons are ours

The theme turns out to be thin here. `x-office-presentation` exists, but there
is no full-colour system-monitor or weather icon at all -- only symbolic glyphs,
which look flat as an app icon. So `icons/` holds one icon per app, named after
the app_id. Change them and re-run `bb install-desktop`.

Either format works: `icons/<app_id>.svg` installs into hicolor's `scalable`
directory, `icons/<app_id>.png` into `512x512`, and the `.desktop` file names
neither -- `Icon=` carries the bare name and the theme lookup picks the file.
Replacing an icon with the other format deletes the one it replaces, because a
scalable SVG left behind would keep beating a 512px PNG.
