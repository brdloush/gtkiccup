# Widgets and props

## Widgets

The core tags. Each row lists what only that widget takes; every widget also
takes the common props below. `gtkiccup.adw` adds more -- see
[libadwaita](#libadwaita).

| tag | own props | events | children |
| --- | --- | --- | --- |
| `:vbox` | `:spacing` (default 0) | -- | any number |
| `:hbox` | `:spacing` (default 0) | -- | any number |
| `:label` | `:label` `:markup` `:wrap` `:xalign` | -- | text only |
| `:button` | `:label` | `:on-click` | text only |
| `:check` | `:label` `:active` | `:on-toggle` | text only |
| `:entry` | `:value` `:placeholder` | `:on-change` `:on-activate` | text only |
| `:bin` | -- | -- | one child, given the whole allocation |
| `:clamp` | `:max` width | -- | one child, centred |
| `:overlay` | -- | -- | one child, plus any number with `:slot :over` floating on top |
| `:icon` | `:icon` (theme name) or `:file` (a path, SVG included), `:size` | -- | -- |
| `:scrolled` | `:hscroll` `:vscroll`: `:never` `:automatic` `:always` `:external` (default never/automatic). Cap its height with CSS `max-height` | -- | one child |

Common props, on every widget:

| prop | value | default when absent |
| --- | --- | --- |
| `:margin` | pixels, applied to all four sides | `0` |
| `:sensitive` | truthy = clickable | enabled |
| `:tooltip` | string | none |
| `:hexpand` `:vexpand` | truthy = take spare space | `false` |
| `:halign` `:valign` | `:fill` `:start` `:end` `:center` `:baseline` | `:fill` |
| `:width` `:height` | a size *request*, i.e. a minimum. Enough to build a bar chart out of boxes | `-1` (natural) |
| `:margin-start` `:margin-end` `:margin-top` `:margin-bottom` | individual margins, which win over `:margin`. How you place a widget at a measured pixel offset inside an `:overlay` | `0` |
| `:focusable` | `false` keeps a widget out of the Tab chain, so it cannot steal a keystroke | `true` |
| `:class` | a CSS class name, or a collection of them | none |

`:markup` is Pango markup and wins over `:label`. Whatever builds it **must
escape first** -- a bare `&` or `<` breaks the label. `deckmd/escape` shows the
shape.

A `GtkBox` child takes its **natural** size unless you set `:hexpand`/`:vexpand`.
A scrolled window's natural height is tiny, so a `:scroll` inside a box without
`:vexpand true` collapses to a sliver -- nothing errors, it just looks broken.

Handler arguments: `:on-click` gets none, `:on-change` and `:on-activate` get the
entry's current text, `:on-toggle` gets the new boolean.

## Keyboard input

`:on-key` works on any widget, but always listens at the **toplevel**, in the
**capture** phase.

Both details are load-bearing. A controller on a widget that never takes focus
would never fire; and in the default *bubble* phase the focused widget sees the
key first -- so a focused button swallows the space bar and activates itself,
which in a typing test silently restarts the run. Buttons that sit next to a
keyboard-driven surface are also worth marking `:focusable false`. The handler gets
a map, and returning truthy stops the key going any further:

```clojure
[:bin {:on-key (fn [{:keys [key ctrl? shift?]}]
                 (case key
                   "Right" (do (next-slide!) true)
                   "Escape" (do (ui/close!) true)
                   nil))}
 ...]
```

The key is a **name**, from `gdk_keyval_name`: `"Right"`, `"space"`, `"Escape"`,
`"Page_Down"`, `"F5"`, `"a"`. No table to maintain. `:char` carries the typed
character for keys that produce one, and is `nil` otherwise.

That `nil` matters: Escape, Tab and BackSpace map to unicode **27, 9 and 8** --
real control characters, not zero. A `(pos? uni)` test would type an Escape into
your document, so `gtkiccup.ffi/keyval-char` requires `>= 32`.

Keys are not an ordinary signal: they arrive through a `GtkEventController`,
with a different callback signature and a return value. That is why entries in
`gtkiccup.core/signals` may carry `:argtypes`, `:rettype`, `:controller` and
`:attach` -- see [Extension points](internals.md#extension-points).

## Text children

Strings and numbers in the body fold into the widget's text prop, so these are
the same thing:

```clojure
[:label "Count: " @n]
[:label {:label (str "Count: " @n)}]
```

The target prop is `:label` for `:label`, `:button` and `:check`, and `:value`
for `:entry`. It is declared per widget as `:text-prop`, and it behaves like any
other prop -- diffed, patched, reactive.

Four things are refused, each saying what to do instead: text on a container
(`[:vbox {} "oops"]`), a prop and text children at once (`[:label {:label "a"}
"b"]`), widget children under a leaf (`[:label "hi" [:button "no"]]`), and a
child that is neither a vector, string, number, seq nor nil.
`test/text_children_test.clj` covers all of it.

Adding a widget is one entry in `gtkiccup.core/widgets`. `:text-prop` is what makes
text children work, so do not leave it out:

```clojure
(ui/register-widget! :label
  {:text-prop :label                                   ; [:label "hi"] fills this
   :ctor  (fn [p] (g/label-new (str (:label p ""))))
   :apply (fn [w p changed]
            (when (contains? changed :label)
              (g/label-set-text w (str (:label p "")))))})
```

A container adds `:append` and `:remove`, each `(fn [parent child-ptr
child-props])`. The child's props are passed so a container can honour a
`:slot` -- see [gtkiccup.adw](#libadwaita).


## Keyed children

A `:key` makes a child an identity rather than a position. Reorder a keyed list
and the widgets move; without keys they stay put and are relabelled.

```clojure
[:vbox {}
 (for [p procs]
   [:label {:key (:pid p)} (:name p)])]
```

Keys must be unique among siblings. `:key` is shorthand for Replicant's
`:replicant/key`.

## Lifecycle hooks

Replicant's hooks work on any widget: `:replicant/on-mount`, `:on-update`,
`:on-unmount` and `:on-render`. They run after GTK is in sync, so the widget is
already in its parent. `ui/widget` gets the widget out of the event:

```clojure
[:label {:replicant/on-mount (fn [e] (remember-label! (ui/widget e)))} "hi"]
```

In parent, but not yet laid out: GTK sizes widgets later, in its own frame, so
a hook on the first render reads a width and height of zero. To measure laid-out
widgets -- text geometry, say -- use `run`'s `:on-layout`, which runs after each
painted frame.

## Handlers as data

A handler can be a fn, or data that is sent to one dispatch fn:

```clojure
(ui/set-dispatch!
 (fn [{:gtkiccup/keys [event args]} [action & params]]
   (case action
     :counter/inc (swap! n + (first params)))))

[:button {:on-click [:counter/inc 1]} "+1"]
```

Data compares equal across renders and can be logged, tested and replayed. A
data handler with no dispatch fn set is refused when the view renders.

## Classes in the tag

`[:label.title.dim "x"]` is a `:label` with the classes `title` and `dim`,
alongside or instead of `:class`.

## libadwaita

`(require '[gtkiccup.adw :as adw])` calls `adw_init` and registers these tags. Pass
`:window adw/window` to `ui/run`.

| tag | own props | slots for children |
| --- | --- | --- |
| `:toolbar-view` | -- | `:top`, `:bottom`, else content |
| `:header-bar` | -- | `:start`, `:end`, else the title widget |
| `:window-title` | `:title` `:subtitle` | -- |
| `:page` | -- | groups |
| `:group` | `:title` `:description` | rows |
| `:row` | `:title` `:subtitle` | `:prefix`, else `:suffix` |
| `:status-page` | `:title` `:description` `:icon` | -- |
| `:toast-overlay` | -- | one child |
| `:bin` `:clamp` | `:clamp` takes `:max` width. Both **replace** the plain-GTK4 tags of the same name with AdwBin and AdwClamp | one child |
| `:scroll` | `:h` `:v` scrollbar policy: `:automatic` `:never` `:always` `:external` | one child |
| `:banner` | `:title` `:revealed` | -- |
| `:spinner` | -- | -- |
| `:carousel` | `:page` (an index -- changing it **animates**), `:animate`, `:drag`, `:wheel` | pages |
| `:revealer` | `:revealed` `:duration` | one child |
| `:level` | `:value` `:min` `:max` `:width` | -- |
| `:picture` | `:file` | -- |
| `:icon-button` | `:icon` | -- |
| `:adw-window` | -- | one child |

A slot is a plain `:slot` prop on the **child**, which the parent's `:append`
reads:

```clojure
[:row {:title "firefox" :subtitle "412 MB"}
 [:icon  {:slot :prefix :icon "application-x-executable-symbolic"}]
 [:level {:slot :suffix :value 0.42}]]
```

`(adw/toast! overlay "message")` posts a toast. libadwaita's own style classes
work through `:class` with no CSS of your own -- `"flat"`, `"dim-label"`,
`"title-1"`, `"pill"`, `"card"`, `"success"`.
