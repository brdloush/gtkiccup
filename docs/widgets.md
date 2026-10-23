# Widgets and props

## Widgets

The core tags. Each row lists what only that widget takes; every widget also
takes the common props below. `gtkiccup.adw` adds more -- see
[libadwaita](#libadwaita).

| tag | own props | events | children |
| --- | --- | --- | --- |
| `:vbox` | `:spacing` (default 0), `:homogeneous` | -- | any number |
| `:hbox` | `:spacing` (default 0), `:homogeneous` | -- | any number |
| `:label` | `:label` `:markup` `:wrap` `:wrap-mode` `:lines` `:ellipsize` `:xalign` `:yalign` `:justify` `:selectable` `:width-chars` `:max-width-chars` | -- | text only |
| `:button` | `:label` `:icon` `:has-frame` (default true) | `:on-click` | text, or one child -- and once a child is set, GTK stops showing `:label` |
| `:check` | `:label` `:active` | `:on-toggle` | text only |
| `:entry` | `:value` `:placeholder` | `:on-change` `:on-activate` | text only |
| `:bin` | -- | -- | one child, given the whole allocation |
| `:clamp` | `:max` width (default -1, i.e. unset; `gtkiccup.adw`'s swap sets 600) | -- | one child, centred, and in plain GTK4 it is a centred `GtkBox` rather than a `GtkClamp`: `:max` becomes a size *request*, so a clamp is also why a window cannot be dragged narrower than that. `:min` is read by nobody here -- it belongs to `AdwClamp` -- and it is applied once, at construction, so raising `:max` on a re-render does nothing until the widget is rebuilt. `test/clamp_test.clj` |
| `:overlay` | -- | -- | one child, plus any number with `:slot :over` floating on top |
| `:icon` | `:icon` (theme name), `:file` (a path, SVG included) or `:texture` (a path to a bitmap, loaded as pixels), `:size` (default -1, GTK's own choice) | -- | text: the icon name itself, so `[:icon "gtk-ok"]` |
| `:scrolled` | `:hscroll` `:vscroll`: `:never` `:automatic` `:always` `:external` (default never/automatic). Cap its height with CSS `max-height`. `:wheel-scroll`: `true` (both axes) `:vertical` `:horizontal` `:both` | -- | one child |
| `:center-box` | -- | -- | children with `:slot :start` / `:end` at the sides, anything else centred; one widget per slot |
| `:separator` | `:orientation` -- `:vertical` draws it across, anything else draws it across horizontally, so a typo reads as `:horizontal` rather than failing | -- | -- |
| `:scale` | `:min` `:max` `:step` `:value` `:draw-value` `:digits` (default 1) `:inverted` `:orientation` | `:on-change-value` | -- |
| `:aspect-frame` | `:ratio` (1.0 = square), `:obey-child` | -- | one child |
| `:menu-button` | `:icon` `:label` `:has-frame` (default false, unlike `:button`) `:primary` | -- | one child, or a menu / popover to open |

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
| `:visible` | `false` hides it. The widget stays alive, so its state survives | `true` |
| `:overflow` | `:visible` `:hidden` `:mask` -- `:hidden` is what lets `border-radius` clip what is inside, which CSS alone cannot do: a rounded cover picture shows square corners without it | `:visible` |
| `:direction` | `:ltr` / `:rtl` text direction | unset means **no call**, so GTK's locale-derived direction stands; `:ltr` is what removal falls back to |
| `:ref` | a name other widgets can find this widget by -- see [gtkiccup.refs](api.md#gtkiccuprefs) | none |

`:markup` is Pango markup and wins over `:label`. Whatever builds it **must
escape first** -- a bare `&` or `<` breaks the label. `deckmd/escape` shows the
shape.

A `GtkBox` child takes its **natural** size unless you set `:hexpand`/`:vexpand`.
A scrolled window's natural height is tiny, so a `:scroll` inside a box without
`:vexpand true` collapses to a sliver -- nothing errors, it just looks broken.

A `:homogeneous` box gives every child the same size. Like `:spacing` it is set
at construction -- the renderer applies props of a freshly built widget once,
so a build-time prop also holds on a page that never re-renders.

`GtkScrolledWindow` is supposed to move on the mouse wheel, and on some setups
it does not: the page scrolls with Page Down but the wheel does nothing, because
the window's own scroll controller never fires. Set `:wheel-scroll` and
gtkiccup drives the adjustment from its own capture-phase controller, which sees
the wheel over the whole content -- including over any nested strip -- and
reports *handled* only when it actually moved, so it never swallows a scroll
the rest of the UI could have used.

A label's shape comes from the props that decide how much of a string fits:
`:ellipsize` truncates (card titles use `:middle` -- the middle of a title is
the part you can afford to lose), `:lines` caps the height of wrapped text, and
`:width-chars` *asks for* a width while `:max-width-chars` *caps* one.
`:selectable` makes it copyable.

Handler arguments: `:on-click` gets none, `:on-change` and `:on-activate` get
the entry's current text, `:on-toggle` gets the new boolean, and
`:on-change-value` gets the new value of a `:scale` -- returning nothing from
it means *not handled*, so GTK moves the knob itself and the state catches up
on the next render. A drag is never fought by the app, and the value is written
back only when it really differs.

One trap belongs to the tag rather than to the handler: `gtk_scale_new_with_range`
asserts that the step is not zero and hands back NULL if it is, so a `:step 0` is
coerced to 1.0 at construction. Dropping the prop later is not the same thing --
`:apply` writes whatever is in the props, so removing `:step` sets the keyboard
increments to zero, which is GTK's own "no stepping" rather than the 1.0 the
button started with.

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

The target prop is `:label` for `:label`, `:button`, `:check` and
`:menu-button`, `:value` for `:entry`, `:icon` for `:icon` and
`:icon-button`, `:title` for `:window-title`, `:row` and `:status-page`,
`:file` for `:picture`. It is declared per widget as `:text-prop`, and it
behaves like any other prop -- diffed, patched, reactive.

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

The props map is yours: `:pid` is not a gtkiccup prop, just a key your own data
carries. The renderer only reads `:key`, and uses it to keep the right widget
when the list's order changes.

Keys must be unique among siblings. `:key` is shorthand for Replicant's
`:replicant/key`.

## Lifecycle hooks

Replicant's hooks work on any widget: `:replicant/on-mount`,
`:replicant/on-update`, `:replicant/on-unmount` and `:replicant/on-render`.
The `:replicant/` prefix is part of the name -- a bare `:on-mount` is not a hook
at all, it is an unknown prop that is silently dropped, so the handler never
runs and nothing says so. (Like `:replicant/key` for `:key`, except that `:key`
is translated for you.) They run after GTK is in sync, so the widget is
already in its parent. `ui/widget` gets the widget out of the event:

```clojure
[:label {:replicant/on-mount (fn [e] (remember-label! (ui/widget e)))} "hi"]
```

In parent, but not yet laid out: GTK sizes widgets later, in its own frame, so
a hook on the first render reads a width and height of zero. To measure laid-out
widgets -- text geometry, say -- use `run`'s `:on-layout`, which runs after each
painted frame.

## Components as aliases

A plain `defn` component re-runs its body on every render, even when nothing it
shows has changed -- a 200-row list rebuilds 200 subtrees once a second while a
track plays. To opt out, register the component once as a **late-bound alias**: a
namespaced keyword plus an expansion fn. The page then emits it as *data*, and
Replicant expands it lazily inside `reconcile` -- only when the attrs differ.

```clojure
(ns myapp
  (:require [gtkiccup.core :as ui]
            [gtkiccup.alias :as ga]))

;; Register the expansion once, at load. `attrs` is plain data and is BOTH the
;; cache key and the body's whole input; `children` is any emitted children.
(ga/register-alias! :app/row
  (fn [{:keys [i label on-click]} _children]
    [:button {:class "row" :on-click on-click} (str i ". " label)]))

;; The page emits DATA nodes, not a call. A row whose attrs are unchanged (`=`)
;; does not re-run the body -- and `on-click` is DATA (a vector), so the row
;; stays =-stable rather than re-keying on a fresh closure every render.
(defn view []
  (let [items (:items @state)]
    [:vbox
     (for [[i item] (map-indexed vector items)]
       [:app/row {:i i :label (:name item)
                  :on-click [:item/select (:id item)]}])]))

;; Data handlers and data hooks land in one dispatch fn, set once.
(ui/set-dispatch!
  (fn [_event [action id]]
    (case action :item/select (select! id))))
```

Three rules make the cache behave:

- **The attrs map is the cache key and the body's entire input.** It must be
  `=`-comparable data -- no fresh closures (use data handlers, [below](#handlers-as-data)).
  A value the body reads from live state goes stale, so the *page* (which does
  run every render) reads state and passes it in as attrs.
- **Near-static config** (theme, country, client id) rides `:alias-data` on
  `run`, merged into every expansion as `:replicant/alias-data`. It is not for
  live state: changing it invalidates every alias.
- **A body may throw safely** -- Replicant catches it and calls
  `:on-alias-exception` (gtkiccup logs it and keeps the frame) rather than
  corrupting the tree. `(ga/alias-stats)` exposes per-alias cold-expansion
  counters, so a cache that never fires is observable, not silent.

The body's result runs through the normal `normalize` + `->replicant` pipeline at
expansion time, so signals, value boxing, data handlers and lifecycle hooks all
still apply -- a hook inside an alias body fires exactly once, on mount. See
[internals](internals.md#rendering) for the data-flow contract and
`test/alias_test.clj` for the behaviour. This replaces the hand-written
`gtkiccup.hiccup/memoized`, removed in goal 11.

Working, test-covered references to copy live in `examples/`: `todo.clj`
(`:todo/row` -- the shape above at its smallest), `monitor.clj` (`:monitor/metric`,
`:monitor/process`), `weather.clj` (`:weather/hour`, `:weather/day`,
`:weather/detail`), and `deck.clj` (`:deck/slide`, `:deck/bullet`). The deck is the
clearest case for the cache: its chrome clock re-renders the page once a second while
the slide maps stay `=`-equal, so a tick re-expands nothing.

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

The canonical form is the positional `[action & args]` vector -- `[:todo/toggle 3]`,
`[:monitor/toast-pid name pid]`, `[:weather/units]`, `[:deck/key]` -- each routed by a
single `ui/set-dispatch!` fn in the matching `examples/` app. The value a signal
*produces* (a `:change`'s text, a `:toggle`'s bool, an `:on-key`'s event map) arrives in
`:gtkiccup/args`, since GTK has no JS event object to interpolate placeholders from. A
handler that must return a value still can: the deck's `:on-key` dispatch returns truthy
only when it consumed the key, so unknown keys fall through to GTK.

## Classes in the tag

`[:label.title.dim "x"]` is a `:label` with the classes `title` and `dim`,
alongside or instead of `:class`.

## libadwaita

`(require '[gtkiccup.adw :as adw])` calls `adw_init` and registers these tags. Pass
`:window adw/window` to `ui/run`.

For a real application window, pass `(adw/application-window "cz.brdloush.MyApp")`
instead: an `AdwApplicationWindow` under an `AdwApplication`. The app is
registered and activated -- startup is emitted, which is what windows need
before they can be added -- but never `g_application_run`: gtkiccup keeps
pumping its own loop, so `later!`, `wake!` and the REPL story are unchanged.
The app registers **non-unique**, so a stuck instance cannot claim the D-Bus
name and hand the next launch's window to a zombie; `app-id` may be nil for a
local app that only parents windows. The app id is a different identity from
`run`'s `:app-id` -- see [Desktop integration](desktop-integration.md).

| tag | own props | slots for children |
| --- | --- | --- |
| `:toolbar-view` | -- | `:top`, `:bottom`, else content |
| `:header-bar` | -- | `:start`, `:end`, else the title widget |
| `:window-title` | `:title` `:subtitle` | -- |
| `:page` | -- | groups |
| `:group` | `:title` `:description` | rows |
| `:row` | `:title` `:subtitle` | `:prefix`, else `:suffix` |
| `:status-page` | `:title` `:description` `:icon` | one child. All three land at construction, which is worth saying: the ctor used to apply only `:title`, so an empty state that asked for a description or an icon showed neither until something *changed* them (`test/adw_test.clj` step 6). Leaving `:icon` unset keeps AdwStatusPage's own default icon rather than clearing it |
| `:toast-overlay` | -- | one child |
| `:bin` `:clamp` | `:clamp` takes `:max` (default **600**, where core's `:clamp` leaves it unset at -1 -- so swapping the tag changes the default too). Both **replace** the plain-GTK4 tags of the same name with AdwBin and AdwClamp | one child |
| `:scroll` | `:h` `:v` scrollbar policy: `:automatic` `:never` `:always` `:external`, both defaulting to `:automatic` | one child |
| `:banner` | `:title` `:revealed` (default `true` -- it is shown until you hide it) | -- |
| `:spinner` | -- | -- |
| `:carousel` | `:page` (an index -- changing it **animates**), `:animate` (default `true`), `:drag` and `:wheel` (both `false`: dragging is not free) | pages |
| `:revealer` | `:revealed` (default `true`), `:duration` (default 250 ms) | one child |
| `:level` | `:value` `:min` `:max` (defaults 0 / 1 / 0, centred vertically) | -- |
| `:picture` | `:file` (`:texture` / `:paintable` are synonyms), `:content-fit` (`:fill` `:contain` `:cover` `:scale-down`, default `:contain`; `:cover` fills a card without letterboxing), `:can-shrink` (default `true`) | -- |
| `:icon-button` | `:icon` | -- |
| `:adw-window` | -- | one child |
| `:overlay-split-view` | `:show-sidebar` (default `true`), `:sidebar-width-fraction` (default 0.4), `:min-sidebar-width` / `:max-sidebar-width` (defaults 320/420 -- measured as fractions of the content since the width unit landed, so pass doubles) | `:slot :sidebar` for the pane that floats over, everything else is the content |
| `:view-stack` | `:visible-child-name` (by name; an unknown name is skipped until that page exists), `:h-homogeneous` (default `true`), `:v-homogeneous` (default `false`) | pages are children with a `:name`; `:title` / `:icon` show on a switcher. `:on-switch` fires with the visible page's name -- including once when the first page is picked at build, which is not a switch, so a handler that counts user switches has to notice that first call (`test/view_stack_test.clj` step 5) |
| `:view-switcher` | `:stack` -- a `:ref` name or a kept pointer --, `:policy` `:wide` (the pill bar) / `:narrow` (the icon row) | -- |

A slot is a plain `:slot` prop on the **child**, which the parent's `:append`
reads:

```clojure
[:row {:title "firefox" :subtitle "412 MB"}
 [:icon  {:slot :prefix :icon "application-x-executable-symbolic"}]
 [:level {:slot :suffix :value 0.42}]]
```

Pages and their switcher pair through a `:ref` name, because a hiccup tree has
no other way to point one node at another:

```clojure
[:view-stack {:ref "pages" :visible-child-name "player"}
 [:bin {:name "player" :title "Player" :icon "media-playback-start-symbolic"} ...]
 [:bin {:name "queue"  :title "Queue"  :icon "view-list-symbolic"}          ...]]

[:view-switcher {:stack "pages" :policy :wide}]
```

The stack may be built after the switcher -- construction order follows the
tree -- so the ref is resolved again in `:after-children`, once the container's
children are all in. See [gtkiccup.refs](api.md#gtkiccuprefs).

`(adw/toast! overlay "message")` posts a toast. libadwaita's own style classes
work through `:class` with no CSS of your own -- `"flat"`, `"dim-label"`,
`"title-1"`, `"pill"`, `"card"`, `"success"`.
