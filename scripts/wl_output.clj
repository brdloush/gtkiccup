(ns wl-output
  "Sets the size and scale of a wlroots compositor's only output, through the
   zwlr_output_manager_v1 protocol. `bb shot` uses it inside the headless cage
   (scripts/headless): the headless output starts at 1280x720 and scale 1, and
   cage is a kiosk that sizes every window to the output. So setting the output
   to a shot's size times 2, at scale 2, gives a window of exactly that logical
   size, rendered at HiDPI.

   It speaks the Wayland wire protocol over the compositor's socket itself:
   wlr-randr does the same job, but is one more thing to install. Only the few
   messages needed here are implemented. Every message is a header (object id,
   then size << 16 | opcode) plus 32-bit little-endian arguments; a string is
   a length that counts its NUL, then bytes padded to 4; a fixed is n * 256.

   `bb -m wl-output 1800 1240 2` from inside the cage, for a manual run."
  (:import (java.net UnixDomainSocketAddress StandardProtocolFamily)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.channels SocketChannel)))

(defn- buf [n] (.order (ByteBuffer/allocate n) ByteOrder/LITTLE_ENDIAN))

(defn- str-arg-size [s] (+ 4 (* 4 (quot (+ (count (.getBytes ^String s "UTF-8")) 1 3) 4))))

(defn- msg
  "args: [:u n] [:i n] [:s str] [:f double]"
  [obj opcode & args]
  (let [size (+ 8 (reduce + (map (fn [[t v]] (if (= t :s) (str-arg-size v) 4)) args)))
        b (buf size)]
    (.putInt b (int obj))
    (.putInt b (int (bit-or (bit-shift-left size 16) opcode)))
    (doseq [[t v] args]
      (case t
        (:u :i) (.putInt b (unchecked-int v))
        :f (.putInt b (int (Math/round (* 256.0 (double v)))))
        :s (let [bs (.getBytes ^String v "UTF-8")
                 padded (- (str-arg-size v) 4)]
             (.putInt b (int (inc (count bs))))
             (.put b bs)
             (dotimes [_ (- padded (count bs))] (.put b (byte 0))))))
    (.flip b)
    b))

(defn- send! [^SocketChannel ch ^ByteBuffer b]
  (while (.hasRemaining b) (.write ch b)))

(defn- read-fully! [^SocketChannel ch n]
  (let [b (buf n)]
    (while (.hasRemaining b)
      (when (neg? (.read ch b)) (throw (ex-info "wayland socket closed" {}))))
    (.flip b)
    b))

(defn- read-event! [ch]
  (let [h (read-fully! ch 8)
        obj (.getInt h)
        w (.getInt h)
        size (bit-shift-right (bit-and w 0xffffffff) 16)
        op (bit-and w 0xffff)]
    {:obj obj :op op :body (read-fully! ch (- size 8))}))

(defn- get-str [^ByteBuffer b]
  (let [n (.getInt b)
        padded (* 4 (quot (+ n 3) 4))
        bs (byte-array padded)]
    (.get b bs)
    (String. bs 0 (max 0 (dec n)) "UTF-8")))

(defn- event!
  "The next event. A wl_display.error (object 1, opcode 0) is fatal to the
   connection, so it throws here instead of being skipped by a caller."
  [ch]
  (let [{:keys [obj op ^ByteBuffer body] :as e} (read-event! ch)]
    (when (and (= obj 1) (= op 0))
      (throw (ex-info "Wayland protocol error"
                      {:object (.getInt body) :code (.getInt body) :message (get-str body)})))
    e))

(defn set-output!
  "Sets the output's mode to width x height pixels and its scale. Needs
   WAYLAND_DISPLAY to point at a wlroots compositor with exactly one output.
   Returns :succeeded, :failed or :cancelled, as the compositor answered."
  [width height scale]
  (let [path    (str (System/getenv "XDG_RUNTIME_DIR") "/" (System/getenv "WAYLAND_DISPLAY"))
        ch      (SocketChannel/open StandardProtocolFamily/UNIX)
        ids     (atom 1)                  ; 1 is wl_display; client ids count up
        new-id! #(swap! ids inc)]
    (try
      (.connect ch (UnixDomainSocketAddress/of path))
      (let [registry (new-id!)
            done-cb  (new-id!)
            _ (send! ch (msg 1 1 [:u registry]))   ; wl_display.get_registry
            _ (send! ch (msg 1 0 [:u done-cb]))    ; wl_display.sync: fires after all globals
            globals (loop [acc {}]
                      (let [{:keys [obj op ^ByteBuffer body]} (event! ch)]
                        (cond
                          (and (= obj registry) (= op 0))   ; wl_registry.global
                          (let [name (.getInt body) iface (get-str body) ver (.getInt body)]
                            (recur (assoc acc iface [name ver])))
                          (= obj done-cb) acc
                          :else (recur acc))))
            [gname gver] (or (get globals "zwlr_output_manager_v1")
                             (throw (ex-info "the compositor has no zwlr_output_manager_v1"
                                             {:globals (sort (keys globals))})))
            mgr (new-id!)
            ;; wl_registry.bind with an untyped new_id: interface, version, id
            _ (send! ch (msg registry 0 [:u gname] [:s "zwlr_output_manager_v1"]
                             [:u (min gver 2)] [:u mgr]))
            ;; the manager announces each head (opcode 0, a server-made id),
            ;; then done(serial) (opcode 1); the serial guards the configuration
            [heads serial] (loop [heads []]
                             (let [{:keys [obj op ^ByteBuffer body]} (event! ch)]
                               (cond
                                 (and (= obj mgr) (= op 0)) (recur (conj heads (.getInt body)))
                                 (and (= obj mgr) (= op 1)) [heads (.getInt body)]
                                 :else (recur heads))))
            _ (when (not= 1 (count heads))
                (throw (ex-info "expected exactly one output" {:heads (count heads)})))
            cfg      (new-id!)
            cfg-head (new-id!)]
        (send! ch (msg mgr 0 [:u cfg] [:u serial]))                ; create_configuration
        (send! ch (msg cfg 0 [:u cfg-head] [:u (first heads)]))    ; enable_head
        (send! ch (msg cfg-head 1 [:i width] [:i height] [:i 0]))  ; set_custom_mode, 0 = any refresh
        (send! ch (msg cfg-head 4 [:f scale]))                     ; set_scale
        (send! ch (msg cfg 2))                                     ; apply
        (loop []
          (let [{:keys [obj op]} (event! ch)]
            (if (= obj cfg)
              (case op 0 :succeeded 1 :failed 2 :cancelled)
              (recur)))))
      (finally (.close ch)))))

(defn -main [& [w h s]]
  (println "output config:" (set-output! (parse-long w) (parse-long h) (parse-double (or s "1")))))
