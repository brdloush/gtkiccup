(ns cage
  "One line of glue between bb.edn's `test` task and scripts/cage-server.

   The rule: a test run attaches to the ONE persistent cage compositor instead
   of opening its windows on the user's GNOME desktop. This is what stops a
   test run from stealing focus every time -- and it is why the task branches:
   `rerun` re-invokes it from inside cage, where `here?` is true, and the task
   then does its real work. The examples, `shot` and `dev` do not use this:
   their windows are meant to be seen."
  (:require [babashka.process :as p]))

(defn inside?
  "True when the current process was started by scripts/cage-server run."
  []
  (some? (System/getenv "GTKICCUP_CAGE")))

(defn here?
  "True when the task should do its work in this process: either it already
   runs inside cage, or GTKICCUP_NO_CAGE asks for the desktop. The opt-out
   has to count as \"here\", not just as \"not inside\": the variable survives
   into the cage run, so treating it as \"not inside\" re-ran the task through
   cage-server forever, one process deeper each time."
  []
  (or (some? (System/getenv "GTKICCUP_NO_CAGE")) (inside?)))

(defn rerun
  "Re-runs `bb <args>` inside the persistent cage, starting cage if it is not
   already up. Replaces this process, so the task's exit code is the inner
   run's -- a failing test file still fails `bb test`."
  [& args]
  (apply p/exec "scripts/cage-server" "run" "bb" args))
