#!/usr/bin/env bb
;; Introspect an enum from a GIR typelib: dump name -> value.
;; Usage: bb dev/enum.clj <namespace> <Type>   e.g. Adw ViewSwitcherPolicy
(ns gtkiccup.dev-enum
  (:require [babashka.ffi :as ffi :refer [defcfn]]
            [gtkiccup.ffi :as g]))

(ffi/load-system-library "girepository-1.0")

(defcfn repo-default "g_irepository_get_default" [] :pointer)
(defcfn repo-require "g_irepository_require" [:pointer :string :string :int :pointer] :pointer)
(defcfn repo-find-by-name "g_irepository_find_by_name" [:pointer :string :string] :pointer)
(defcfn value-get "g_value_info_get_value" [:pointer] :long)
(defcfn base-name "g_base_info_get_name" [:pointer] :string)
(defcfn enum-n-values "g_enum_info_get_n_values" [:pointer] :int)
(defcfn enum-value "g_enum_info_get_value" [:pointer :int] :pointer)
(defcfn value-int "g_value_info_get_value" [:pointer] :long)

  (let [[ns type version] *command-line-args*
      repo (repo-default)
      _    (repo-require repo ns (or version "1") 0 ffi/null)
      info (repo-find-by-name repo ns type)]
  (if (g/null? info)
    (println "not found:" ns type)
    (let [n (enum-n-values info)]
      (println type "values:")
      (doseq [i (range n)]
        (let [v (enum-value info i)]
          (println " " (base-name v) (value-int v)))))))
