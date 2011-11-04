(ns avout.atoms
  (:require [zookeeper :as zk]
            [zookeeper.data :as data]
            [avout.locks :as locks])
  (:import (clojure.lang IRef)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PROTOCOLS

(defprotocol AtomState
  (getValue [this] "Returns a map containing the :value and a :version
    which may just be the current value or a version number and is used in
    compareAndSet to determine if an update should occur.")
  (setValue [this value]))

(defprotocol AtomReference
  (swap [this f] [this f args])
  (reset [this new-value])
  (compareAndSet [this old-value new-value]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DistributedAtom implementation

(defn trigger-watchers
  [client node-name]
  (zk/set-data client node-name (data/to-bytes 0) -1))

(deftype DistributedAtom [client nodeName atomData validator watches lock]
  AtomReference
  (compareAndSet [this old-value new-value]
    (if (and @validator (not (@validator new-value)))
      (throw (IllegalStateException. "Invalid reference state"))
      (locks/with-lock (.writeLock lock)
        (let [value (.getValue atomData)]
          (and (= old-value value)
               (.setValue atomData new-value))))))

  (swap [this f] (.swap this f nil))

  (swap [this f args]
    (locks/with-lock (.writeLock lock)
      (let [new-value (apply f (.getValue atomData) args)]
        (if (and @validator (not (@validator new-value)))
          (throw (IllegalStateException. "Invalid reference state"))
          (do (.setValue atomData new-value)
              (trigger-watchers client nodeName)
              new-value)))))

  (reset [this new-value]
    (locks/with-lock (.writeLock lock)
      (if (and @validator (not (@validator new-value)))
        (throw (IllegalStateException. "Invalid reference state"))
        (do (.setValue atomData new-value)
            (trigger-watchers client nodeName)
            new-value))))

  IRef
  (deref [this]
    (.getValue atomData))

  (addWatch [this key callback] ;; callback params: akey, aref, old-val, new-val, but old-val will be nil
    (let [watcher (fn watcher-fn [event]
                    (when (contains? @watches key)
                      (when (= :NodeDataChanged (:event-type event))
                       (let [new-value (.deref this)]
                         (callback key this nil new-value)))
                      (zk/exists client nodeName :watcher watcher-fn)))]
      (swap! watches assoc key watcher)
      (zk/exists client nodeName :watcher watcher)
      this))

  (getWatches [this] @watches)

  (removeWatch [this key] (swap! watches (dissoc key)) this)

  (setValidator [this f] (reset! validator f))

  (getValidator [this] @validator))

(defn distributed-atom [client name atom-data & {:keys [validator]}]
  (zk/create client name :persistent? true)
  (DistributedAtom. client name atom-data
                    (atom validator) (atom {})
                    (locks/distributed-read-write-lock client :lock-node (str name "/lock"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Versions of Clojure's Atom functions for use with AtomReferences

(defn swap!!
  "Cannot use standard swap! because Clojure expects a clojure.lang.Atom."
  ([atom f & args] (.swap atom f args)))

(defn reset!!
  "Cannot use standard reset! because Clojure expects a clojure.lang.Atom."
  ([atom new-value] (.reset atom new-value)))

(defn compare-and-set!!
  "Cannot use standard reset! because Clojure expects a clojure.lang.Atom."
  ([atom old-value new-value] (.compareAndSet atom old-value new-value)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Default implementation of AtomReference that uses ZooKeeper has the data value container.

(defn serialize-form
  "Serializes a Clojure form to a byte-array."
  ([form]
     (data/to-bytes (pr-str form))))

(defn deserialize-form
  "Deserializes a byte-array to a Clojure form."
  ([form]
     (read-string (data/to-string form))))

(deftype ZKAtomState [client dataNode]
  AtomState
  (getValue [this]
    (let [{:keys [data stat]} (zk/data client dataNode)]
      (deserialize-form data)))

  (setValue [this new-value] (zk/set-data client dataNode (serialize-form new-value) -1)))

(defn zk-atom
  ([client name init-value]
     (doto (zk-atom client name)
       (.reset init-value)))
  ([client name]
     (distributed-atom client name (ZKAtomState. client (zk/create-all client (str name "/data"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Usage examples
(comment

  (use 'avout.atoms :reload-all)
  (require '[zookeeper :as zk])

  (def client (zk/connect "127.0.0.1"))
  (def a0 (zk-atom client "/a1" 0))
  @a0
  (swap!! a0 inc)
  @a0

  (def a1 (zk-atom client "/a1" {}))
  @a1
  (swap!! a1 assoc :a 1)
  (swap!! a1 update-in [:a] inc)

  ;; check that reads are not blocked by writes
  (future (swap!! a1 (fn [v] (Thread/sleep 5000) (update-in v [:a] inc))))
  @a1

  ;; test watches
  (add-watch a1 :a1 (fn [akey aref old-val new-val] (println akey aref old-val new-val)))
  (swap!! a1 update-in [:a] inc)
  (swap!! a1 update-in [:a] inc)
  (remove-watch a1 :a1)

  )