(ns i18n
  "The smallest thing that deserves the name: a dictionary per language, one
   lookup function, and a root binding for the language in force.

   `*lang*` is a root binding rather than an argument threaded through every
   pure function. A locale is ambient -- it is a property of the person at the
   keyboard, not of a temperature -- and threading it would put a `lang`
   parameter on `condition`, `day-name`, `view-model` and everything that calls
   them. `set-lang!` writes the root, so a worker thread sees it too; tests can
   still `binding` it around one expression.

   A missing key falls back to English, then to the key itself, so a half
   translated dictionary shows English rather than blowing up mid render."
  (:require [clojure.string :as str]))

(def languages
  "In display order. `:en` is also the fallback."
  [:en :cs])

(def language-names
  "What the switcher shows. Each language names itself."
  {:en "EN" :cs "CS"})

(defonce ^{:doc "lang -> {key -> string-or-vector}. An atom so an app can
  register its own strings without this namespace knowing they exist."}
  dictionaries
  (atom {}))

(defn register!
  "Merges `{:en {...} :cs {...}}` into the dictionaries."
  [dicts]
  (swap! dictionaries #(merge-with merge % dicts))
  nil)

(def ^:dynamic *lang* :en)

(defn supported?
  [lang]
  (boolean (some #{lang} languages)))

(defn set-lang!
  "Sets the language for every thread. Anything unsupported becomes :en."
  [lang]
  (let [lang (if (supported? lang) lang :en)]
    (alter-var-root #'*lang* (constantly lang))
    lang))

(defn detect
  "The language from the environment, defaulting to English. POSIX locale
   names look like `cs_CZ.UTF-8`, so the first two letters are the language."
  ([] (detect (or (System/getenv "LC_ALL")
                  (System/getenv "LC_MESSAGES")
                  (System/getenv "LANG"))))
  ([locale]
   (let [s    (some-> locale str/trim str/lower-case)
         code (when (and s (>= (count s) 2)) (keyword (subs s 0 2)))]
     (if (supported? code) code :en))))

(defn t
  "The string for `k` in the language in force. Extra arguments go through
   `format`, so a translation decides where the number lands in the sentence.

   A translation may also be a **function**, called with the arguments instead.
   That is what a language with real inflection needs: Czech says `2 dny` but
   `5 dni`, and the rule belongs in the Czech dictionary, not in the caller.

   A value that is neither -- a vector of weekday names, say -- comes back
   untouched."
  [k & args]
  (let [dicts @dictionaries
        v (or (get-in dicts [*lang* k])
              (get-in dicts [:en k])
              ;; the whole keyword, so a missing string names itself on screen
              (str (symbol k)))]
    (cond
      (fn? v)                    (apply v args)
      (and (string? v) (seq args)) (apply format v args)
      :else                      v)))
