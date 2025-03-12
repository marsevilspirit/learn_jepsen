(ns jepsen.etcdemo
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [verschlimmbesserung.core :as v]
            [jepsen [cli :as cli]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def dir "/opt/etcd")
(def binary "etcd")
(def logfile (str dir "/etcd.log"))
(def pidfile (str dir "/etcd.pid"))

(defn node-url
  "An HTTP url for connectiong to a node on a particular port."
  [node port]
  (str "http://" node ":" port))

(defn peer-url
  "The HTTP url for other peers to talk to a node."
  [node]
  (node-url node "2380"))

(defn client-url
  "The HTTP url for clients to talk to a node."
  [node]
  (node-url node "2379"))

(defn initial-cluster
  "Constructs an initial cluster string for a test, like
  \"foo=foo:2380,bar=bar:2380,...\""
  [test]
  (->> (:nodes test)
       (map (fn [node]
              (str node "=" (peer-url node))))
       (str/join ",")))

(defn db
  "Etcd DB for a particular version."
  [version]
  (reify db/DB ; the db function uses reify to construct a new object satisfying jepsen's DB protocol (from the db namespace).
    ; setup! and teardown! are the two methods that must be implemented.
    (setup! [_ test node]
      (log/info node "installing etcd" version)
      (c/su
       (let [url (str "https://storage.googleapis.com/etcd/" version "/etcd-" version "-linux-amd64.tar.gz")]
         (cu/install-archive! url dir)))
      (log/info node "downloaded etcd" version)

      (cu/start-daemon! ; opts bin args
       {:logfile logfile
        :pidfile pidfile
        :chdir   dir}
       binary
       :--log-output                   :stderr
       :--name                         (name node)
       :--listen-peer-urls             (peer-url   node) ; port: 2380
       :--listen-client-urls           (client-url node) ; port: 2379
       :--advertise-client-urls        (client-url node)
       :--initial-cluster-state        :new
       :--initial-advertise-peer-urls  (peer-url node)
       :--initial-cluster              (initial-cluster test))

      (Thread/sleep 10000)) ; wait for etcd to start

    (teardown! [_ test node]
      (log/info node "tearing down etcd")
      (cu/stop-daemon! binary pidfile)
      (c/su (c/exec :rm :-rf dir)) ; TODO: rm comment
      )

    db/LogFiles
    (log-files [_ test node]
      [logfile])))

(defn r [_ _] {:type :invoke, :f :read, :value nil})
(defn w [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (log/info node (client-url node))
    (assoc this :conn (v/connect (client-url node)
                                 {:timeout 5000})))

  (setup! [this test])

  (invoke! [this test op]
    (case (:f op)
      :read (assoc op :type :ok, :value (v/get conn "foo"))))

  (teardown! [this test])

  (close! [_ test]
    ; If out connection were stateful, we'd close it here. Verschlimmmbesserung
    ; doesn't actually hold connections, so there's nothing to close
    ))

(defn etcd-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test ; noop-test means none test to run
         opts
         {:pure-generators true
          :name            "etcd"
          :os              debian/os
          :db              (db "v3.1.5") ; use etcd v3.1.5
          :client          (Client. nil)
          :generator       (->> r
                                (gen/stagger 1) ; a certain amount of random delay between operations
                                (gen/nemesis nil)
                                (gen/time-limit 15))})) ; the time limit for the test

(defn -main
  "Handles command line arguments.
  Can either run a test, or a web server for browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test}) ; main test
                   (cli/serve-cmd)) ; serve a web browser to explore the history of our test results
            args))
