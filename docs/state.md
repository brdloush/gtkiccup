# Working with a page: where state is read

gtkiccup follows the **top-down rendering** model: the UI is a pure function of
the application state. Whenever state changes, you call one function that takes
the state and returns the whole interface as hiccup -- data in, data out. That
model is Replicant's; see its own material for the inspiration and the reasoning
behind it:

- [Top-down rendering](https://replicant.fun/top-down/) -- why one pure function
  over the whole state is a better programming model than stateful components.
- [Replicant homepage](https://replicant.fun/) -- "State (data) in, hiccup
  (data) out. No local state, no atoms, no subscriptions... just a pure function
  from data to hiccup."

gtkiccup is the host, so *it* owns the reactive atom (`gtkiccup.ratom`) and the
loop that re-runs the render on a change. That single fact decides where reading
state is legal and where it is not, and it is the one rule this page exists to
make obvious.

## The rule

> **Deref the atom at the page -- the root render fn the host re-runs. Every
> value handed to a child render helper or into an alias node's attrs is
> already-deref'd plain data. Children and alias bodies are pure functions of
> their arguments.**

Two reasons, and they are different:

1. **Correctness at the page is free.** The reactive atom's watch marks the tree
   dirty on *any* change (`swap!` or `reset!`), and the loop re-runs the whole
   render fn. So a page that derefs `@state` can never go stale -- it runs again
   the moment anything changes. Reading the atom there is the sanctioned edge.

2. **Purity is enforced below it, because of the alias cache.** A late-bound
   alias (`ga/register-alias!`) is expanded lazily and cached: the attrs map *is*
   the cache key and *is* the body's entire input, and on a hit the body does not
   run. A value the body pulled from live state would therefore freeze -- it only
   refreshes when an attr changes. So anything live must arrive as an attr, read
   by the page. This is why child helpers and alias bodies must not deref the atom
   or the language themselves.

The corollary for **what** to pass down: hand children the *sub-states they
render*, not the whole state map. An alias keyed on the whole map re-expands on
every unrelated change (the arg `=`-differs each tick), which silently defeats
the cache -- the same way a fresh closure in an attr would. Narrow attrs are what
make the memoization pay.

## The page derefs; children are pure

The cleanest worked example is `examples/todo.clj`. The page reads the atom once
and emits alias nodes carrying the deref'd row fields as plain attrs:

```clojure file=examples/todo.clj
(defn home []
  (let [{:keys [draft items]} @state]
    [:vbox {:spacing 10 :margin 16}
     [:hbox {:spacing 8}
      [:entry {:value draft
               :hexpand true
               :on-change [:todo/draft]
               :on-activate [:todo/add]}]
      [:button {:label "Add" :on-click [:todo/add]
                :sensitive (seq (str/trim draft))}]]
     [:vbox {:spacing 4}
      (map-indexed (fn [i {:keys [text done?]}]
                     [:todo/row {:i i :text text :done? done?}])
                   items)]
     [:label {:label (str (count (filter :done? items)) " / " (count items) " done")}]]))
```

The row is the alias whose body sees only `{:i :text :done?}` -- no atom, no
state, nothing live. That is what lets an unchanged row be skipped on a re-render:

```clojure file=examples/todo.clj
(ga/register-alias! :todo/row
  {:purity :pure}
  (fn [{:keys [i text done?]} _children]
    [:hbox {:spacing 8}
     [:check {:label text :active done? :hexpand true
              :on-toggle [:todo/toggle i]}]
     [:button {:label "x" :on-click [:todo/delete i]}]]))
```

## Deref at the edge, hand the map to the page

When the page takes the state as an argument, pass it the **deref'd value**, never
the atom -- an `r/atom` is a mutable reference, so a helper typed `[state]` that
derefs inside is not a function of its arguments (two calls, same argument,
different output). `examples/monitor.clj` derefs once in `app` and hands the map
to `home`, which forwards slices to `dashboard`:

```clojure file=examples/monitor.clj
(defn app []
  ;; one synchronous reading so the first frame already has numbers
  (reset! state (sys/view-model nil (sys/sample)))
  ;; the single deref: the atom is read once here, at the page edge, and the
  ;; resulting map is what `home` and its children see. No atom is passed down.
  (fn [] (home @state)))
```

`home` takes the plain map (`vm`) and hands it down; it derefs nothing itself:

```clojure file=examples/monitor.clj
(defn home [vm]
  ;; `home` is the page: the host re-runs it on every change, so it is the one
  ;; place allowed to look at live state. `app` (below) deref-s the atom once and
  ;; hands the resulting map here, so `home` and everything under it
  ;; (`dashboard`, the alias rows) are pure functions of their arguments.
  [:toast-overlay {}
   [:toolbar-view {}
    [:header-bar {:slot :top}
     [:window-title {:title "System Monitor"
                     :subtitle (if-let [s vm]
                                 (str (:host s) "  ·  babashka + GTK4")
                                 "starting")}]
     [:icon-button {:slot :end
                    :icon "view-refresh-symbolic"
                    :tooltip "Sample now"
                    :on-click [:monitor/sample]}]]
    [:scroll {}
     (if-let [s vm] (dashboard s) (loading))]]])
```

And the alias body is pure over exactly the attrs the page chose to pass:

```clojure file=examples/monitor.clj
(ga/register-alias! :monitor/metric
  {:purity :pure}
  (fn [{:keys [icon title metric class width]} _children]
    [:row {:title title
           :subtitle (:label metric)
           :class (classes class)}
     [:icon {:slot :prefix :icon icon}]
     [:level {:slot :suffix
              :value (or (:value metric) 0)
              :width (or width 170)
              :class (into ["metric"] (classes class))}]]))
```

## The clock is state too

A wall-clock read inside a helper breaks purity the same way an atom read does.
`examples/deck.clj` re-renders once a second so its elapsed counter moves; the
page samples the clock at the edge and passes `now-ms` in, so `chrome` is still a
function of its arguments:

```clojure file=examples/deck.clj
(defn chrome
  "Slide counter and elapsed time. A revealer so it can fade out.
   `now-ms` is the clock reading, passed in by the page (which samples
   `System/currentTimeMillis` once per render). Taking it as an argument keeps
   `chrome` a pure function of its arguments -- the whole point of the page/child
   split: nothing under the page reads a clock or the atom itself."
  [{:keys [index slides chrome? started-at]} now-ms]
  [:revealer {:revealed (boolean chrome?) :duration 200}
   [:hbox {:spacing 16 :class "chrome" :halign :center}
    [:label {:class "counter" :label (str (inc index) " / " (max 1 (count slides)))}]
    [:label {:label (elapsed (- now-ms started-at))}]
    [:label {:label "← →  ·  F5 fullscreen  ·  Esc quit"}]]])
```

The page reads the atom once (`app` -> `home @state`) and samples the clock
itself, feeding both pure helpers:

```clojure file=examples/deck.clj
(defn home [s]
  ;; `s` is the plain state map the page produced (see `app`), not the atom. The
  ;; clock is sampled here -- at the edge -- and handed to `chrome`, which stays
  ;; pure over its arguments.
  (let [{:keys [slides index]} s
        now (System/currentTimeMillis)]
    [:bin {:on-key [:deck/key]}
     [:vbox {:spacing 0}
      (if (seq slides)
        (into [:carousel {:page index :animate true :vexpand true}]
              (map (fn [sl] [:deck/slide sl]) slides))
        [:bin {:vexpand true} (empty-deck (:path s))])
      (chrome s now)]]))
```

```clojure file=examples/deck.clj
(defn app []
  ;; the single deref, at the page edge: read the atom once, hand the map to the
  ;; pure `home`. The clock is sampled inside `home` (also the edge) for `chrome`.
  (fn [] (home @state)))
```

## The one exception: near-static config (the i18n side-chain)

Some values are *effectively* constant for the life of a render but are not part
of the domain state -- the active theme, the catalogue country, the locale/i18n
dictionary. Replicant gives these a dedicated rail: `:alias-data` on `ui/run` is
merged into every expansion's attrs as `:replicant/alias-data`. It is the
sanctioned way to reach an alias body with ambient config *without* threading it
through every call site.

The catch: changing `:replicant/alias-data` invalidates **every** alias expansion
(it is folded into every key), so it is for near-static config only -- never for
live, frequently-changing state, which belongs in ordinary narrow attrs.

For language specifically, prefer resolving the strings at the page and passing
them as plain attrs, exactly like any other live value: a language switch then
moves only the attrs that carry text. `examples/weather.clj` does this -- the
page builds a `lbl` map of every localized string once, and no child helper or
alias body calls `t`:

```clojure file=examples/weather.clj
        lbl {:now (t :ui/now)
             :feels-like (t :ui/feels-like)
             :hi (t :ui/hi)
             :lo (t :ui/lo)
             :week-title (t :ui/week)
             :details-title (t :ui/details)
             :loading-title (t :ui/loading)
             :loading-sub (t :ui/loading-sub)
             :failed-title (t :ui/failed)}]
```

That map is threaded down, so even the zero-arg-looking states take their strings
as arguments:

```clojure file=examples/weather.clj
(defn loading [lbl]
  ;; the strings come in from the page's `lbl`; nothing here calls `t`.
  [:status-page {:icon "weather-few-clouds-symbolic"
                 :title (:loading-title lbl)
                 :description (:loading-sub lbl)}])
```

```clojure file=examples/weather.clj
(defn failed [err lbl]
  ;; the title comes in from the page's `lbl`; only the raw error string is local.
  [:status-page {:icon "network-offline-symbolic"
                 :title (:failed-title lbl)
                 :description (str err)}])
```

And the `hero` child receives the now-label and captions pre-localized, so a
language switch (which re-runs the page) re-resolves them; the now-cell gets the
string as an attr, putting it in the cache key so only that cell re-expands:

```clojure file=examples/weather.clj
(defn hero [{:keys [place current today hourly sky-class]} {:keys [now feels-like hi lo]}]
  ;; The now-label and the feels/hi/lo captions arrive pre-localized from the
  ;; page; `hero` resolves no language itself. The hourly alias gets the now-string
  ;; as a plain attr, putting it in the cache key -- a language switch (which
  ;; re-runs the page) moves that one attr and re-expands only the now-cell.
  [:bin {:class ["hero" sky-class]}
   [:vbox {:spacing 2 :margin 18}
    [:label {:class "place" :halign :center
             :label (str (:name place)
                         (when (:country place) (str "  ·  " (:country place))))}]
    [:label {:class "temp" :halign :center :label (:temp current)}]
    [:hbox {:spacing 8 :halign :center}
     [:icon {:icon (:icon current) :size 22}]
     [:label {:class "condition" :label (:label current)}]]
    [:label {:class "sub" :halign :center
             :label (str feels-like " " (:feels current)
                         "   ·   " hi " " (:hi today)
                         "   " lo " " (:lo today))}]
    [:scroll {:h :automatic :v :never :hexpand true :margin 8 :class "hourly"}
     (into [:hbox {:spacing 2 :halign :center}]
           (map-indexed (fn [i {:keys [time temp-label precip icon]}]
                          (let [now? (zero? i)]
                            [:weather/hour {:now? now?
                                            :label (if now? now time)
                                            :temp-label temp-label
                                            :precip precip
                                            :icon icon}]))
                        hourly))]]])
```

The alias bodies stay pure because every string they draw was handed in -- here
`:weather/hour` renders pre-resolved `label`/`temp-label`, never `t`:

```clojure file=examples/weather.clj
(ga/register-alias! :weather/hour
  {:purity :pure}
  (fn [{:keys [now? label temp-label precip icon]} _children]
    [:vbox {:spacing 3 :halign :center
            :class (if now? ["hour" "now"] ["hour"])}
     [:label {:class "h-time" :halign :center :label label}]
     [:icon {:icon icon :size 20 :halign :center}]
     [:label {:class "h-temp" :halign :center :label temp-label}]
     [:label {:class "h-pop" :halign :center
              :label (if (and precip (pos? precip)) (str precip "%") " ")}]]))
```

`dashboard` forwards both `vm` and `lbl` onward and reads its group titles from
`lbl`:

```clojure file=examples/weather.clj
(defn dashboard [vm lbl]
  ;; day and detail rows are emitted as ALIAS nodes (plain data); each value is
  ;; handed down as an attr so the expansions stay pure over their args. The two
  ;; group titles are localized strings resolved by the page and passed in via
  ;; `lbl` -- `dashboard` calls no `t`. `vm` and `lbl` are forwarded to `hero`.
  (let [{:keys [daily details place]} vm]
    [:vbox {:spacing 0}
     (hero vm lbl)
     [:clamp {:max 620}
      [:vbox {:spacing 14 :margin 14}
       (into [:group {:title (:week-title lbl)}]
             (map (fn [{:keys [day icon hi lo precip]}]
                    [:weather/day {:day day :icon icon :hi hi :lo lo :precip precip}])
                  daily))
       (into [:group {:title (:details-title lbl)
                      :description (str (:timezone place)
                                        (when (:elevation place)
                                          (format "  ·  %.0f m" (:elevation place))))}]
             (map (fn [{:keys [title value icon]}]
                    [:weather/detail {:title title :value value :icon icon}])
                  details))]]]))
```

## Checklist

- The atom is deref-ed at the page (`app`'s returned thunk, or a
  zero-arg page that closes over the global `defonce`), never inside a child.
- Pass children the **value**, not the atom. A param named `state` that you then
  `@` is a smell.
- Pass the **sub-states** a component renders, not the whole map -- whole-map attrs
  re-expand every frame.
- Alias bodies read **only their attrs**; register with `{:purity :pure}`.
- Read the clock, `swap!`, network, etc. at the edge, never inside a child or body.
- Near-static config may ride `:alias-data` / `:replicant/alias-data`; live values
  (including language strings) go through narrow attrs.

See [widgets.md](widgets.md#components-as-aliases) for the alias mechanics and
[internals.md](internals.md#rendering) for the data-flow contract. Every fenced
block on this page is checked verbatim against the `examples/` file it names by
`scripts/examples-docs-check.clj`.
