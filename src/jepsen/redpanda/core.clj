(ns jepsen.redpanda.core
  "Entry point for command line runner. Constructs tests and runs them."
  (:require [clojure [string :as str]]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [generator :as gen]
                    [tests :as tests]
                    [util :as util :refer [parse-long]]]
            [jepsen.os.debian :as debian]
            [jepsen.redpanda [db :as db]
                             [nemesis :as nemesis]]
            [jepsen.redpanda.workload [list-append :as list-append]
                                      [queue :as queue]]))

(def workloads
  "A map of workload names to workload constructor functions."
  {:list-append list-append/workload
   :queue       queue/workload})

(def nemeses
  "The types of faults our nemesis can produce"
  #{:pause :kill :partition :clock :membership})

(def special-nemeses
  "A map of special nemesis names to collections of faults"
  {:none      []
   :standard  [:pause :kill :partition :clock]
   :all       [:pause :kill :partition :clock]})

(def db-targets
  "Valid targets for DB nemesis operations."
  #{:one :primaries :minority-third :majority :all})

(def partition-targets
  "Valid targets for partition nemesis operations."
  #{:one :primaries :minority-third :majority-ring})

(def standard-nemeses
  "A collection of partial options maps for various nemeses we want to run as a
  part of test-all."
  [{:nemesis nil}
   {:nemesis #{:partition}}
   {:nemesis #{:kill}}
   {:nemesis #{:pause}}
   {:nemesis #{:membership}}
   {:nemesis #{:clock}}
   {:nemesis #{:kill :partition :clock :membership}}])

(defn parse-comma-kws
  "Takes a comma-separated string and returns a collection of keywords."
  [spec]
  (->> (str/split spec #",")
       (remove #{""})
       (map keyword)))

(defn parse-nemesis-spec
  "Takes a comma-separated nemesis string and returns a collection of keyword
  faults."
  [spec]
  (->> (parse-comma-kws spec)
       (mapcat #(get special-nemeses % [%]))
       set))

(def logging-overrides
  "New logging levels for various Kafka packages--otherwise this test is going
  to be NOISY"
  {"org.apache.kafka.clients.FetchSessionHandler"                    :warn
   ; This complains about invalid topics during partitions, too
   "org.apache.kafka.clients.Metadata"                               :fatal
   ; This is going to give us all kinds of NOT_CONTROLLER or
   ; UNKNOWN_SERVER_ERROR messages during partitions
   "org.apache.kafka.clients.NetworkClient"                          :error
   "org.apache.kafka.clients.admin.AdminClientConfig"                :warn
   "org.apache.kafka.clients.admin.KafkaAdminClient"                 :warn
   "org.apache.kafka.clients.admin.internals.AdminMetadataManager"   :warn
   "org.apache.kafka.clients.consumer.ConsumerConfig"                :warn
   "org.apache.kafka.clients.consumer.internals.ConsumerCoordinator" :warn
   ; This is also going to kvetch about unknown topic/partitions when listing
   ; offsets
   "org.apache.kafka.clients.consumer.internals.Fetcher"             :error
   "org.apache.kafka.clients.consumer.internals.SubscriptionState"   :warn
   "org.apache.kafka.clients.consumer.KafkaConsumer"                 :warn
   "org.apache.kafka.clients.producer.KafkaProducer"                 :warn
   ; Comment this to see the config opts for producers
   "org.apache.kafka.clients.producer.ProducerConfig"                :warn
   ; We're gonna get messages constantly about NOT_LEADER_OR_FOLLOWER whenever
   ; we create a topic
   "org.apache.kafka.clients.producer.internals.Sender"              :error
   "org.apache.kafka.common.metrics.Metrics"                         :warn
   "org.apache.kafka.common.utils.AppInfoParser"                     :warn
   })

(defn short-version
  "Truncates a debian version string like 21.11.2-1-f58e69b6 to just 21.11.2"
  [version]
  (if-let [[m short] (re-find #"^([\d\.]+)-" version)]
    short
    version))

(defn stats-checker
  "A modified version of the stats checker which doesn't care if :crash ops
  always crash."
  []
  (let [c (checker/stats)]
    (reify checker/Checker
      (check [this test history opts]
        (let [res (checker/check c test history opts)]
          (if (every? :valid? (vals (dissoc (:by-f res) :crash)))
            (assoc res :valid? true)
            res))))))

(defn perf-checker
  "A modified perf checker which doesn't render debug-topic-partitions, assign,
  or crash operations."
  [perf-opts]
  (let [c (checker/perf perf-opts)]
    (reify checker/Checker
      (check [this test history opts]
        (checker/check c test
                       (->> history
                            (remove (comp #{:assign
                                            :crash
                                            :debug-topic-partitions}
                                          :f)))
                       opts)))))

(defn redpanda-test
  "Constructs a test for RedPanda from parsed CLI options."
  [opts]
  (info :version (pr-str (:version opts)))
  (let [workload-name (:workload opts)
        workload      ((workloads workload-name) opts)
        db            (db/db)
        nemesis       (nemesis/package
                        {:db        db
                         :nodes     (:nodes opts)
                         :faults    (:nemesis opts)
                         :partition {:targets (:partition-targets opts)}
                         :clock     {:targets (:db-targets opts)}
                         :pause     {:targets (:db-targets opts)}
                         :kill      {:targets (:db-targets opts)}
                         :interval  (:nemesis-interval opts)})]
    (merge tests/noop-test
           opts
           {:name      (str (short-version (:version opts))
                            " " (name workload-name)
                            " "
                            (->> opts :sub-via (map name) sort (str/join ","))
                            (when-let [acks (:acks opts)] (str " acks=" acks))
                            (when-let [r (:retries opts)] (str " retries=" r))
                            (when-let [aor (:auto-offset-reset opts)]
                              (str " aor=" aor))
                            (when-let [r (:default-topic-replications opts)]
                              (str " default-r=" r))
                            (when (contains?
                                    opts :enable-server-auto-create-topics)
                              (str " auto-topics=" (:enable-server-auto-create-topics opts)))
                            (when-let [n (:nemesis opts)]
                              (str " " (->> n (map name) sort (str/join ",")))))
            :db        db
            :os        debian/os
            :client    (:client workload)
            :nemesis   (:nemesis nemesis)
            :generator (let [fg (:final-generator workload)]
                         (gen/phases
                           (->> (:generator workload)
                                (gen/stagger    (/ (:rate opts)))
                                (gen/nemesis    (:generator nemesis))
                                (gen/time-limit (:time-limit opts)))
                           (gen/nemesis (:final-generator nemesis))
                           (when fg
                             (gen/phases
                               (gen/log "Waiting for recovery")
                               (gen/sleep 10)
                               ; Redpanda might not give consumers elements
                               ; they want to see, so we eventually give up
                               ; here
                               (gen/time-limit (:final-time-limit opts)
                                 (gen/clients
                                   (:final-generator workload)))))))
            :checker   (checker/compose
                         {:stats      (stats-checker)
                          :clock      (checker/clock-plot)
                          :perf       (perf-checker
                                        {:nemeses (:perf nemesis)})
                          :ex         (checker/unhandled-exceptions)
                          :assert     (checker/log-file-pattern
                                        #"\] assert -" "redpanda.log")
                          :workload   (:checker workload)})
            :perf-opts {:nemeses (:perf nemesis)}
            :logging {:overrides logging-overrides}})))

(def validate-non-neg
  [#(and (number? %) (not (neg? %))) "Must be non-negative"])

(def cli-opts
  "Command line options."
  [[nil "--acks ACKS" "What level of acknowledgement should our producers use? Default is unset (uses client default); try 1 or 'all'."
    :default nil]

   [nil "--auto-offset-reset BEHAVIOR" "How should consumers handle it when there's no initial offset in Kafka?"
   :default nil]

   [nil "--crash-clients" "If set, periodically crashes clients and forces them to set up fresh consumers/producers/etc."
    :default false]

   [nil "--crash-client-interval" "Roughly how long in seconds does a single client get to run for before crashing?"
    :default 30
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "must be a positive number"]]

   [nil "--db-targets TARGETS" "A comma-separated list of nodes to pause/kill/etc; e.g. one,all"
    ;:default [:primaries :all]
    :default [:one :all]
    :parse-fn parse-comma-kws
    :validate [(partial every? db-targets) (cli/one-of db-targets)]]

   [nil "--default-topic-replications INT" "If set, sets Redpanda's default topic replications to this factor. If unset, leaves it as the default value."
    :default nil
    :parse-fn parse-long
    :validate [pos? "must be positive"]]

   [nil "--enable-auto-commit" "If set, disables automatic commits via Kafka consumers. If not provided, uses the client default."
    :default  nil
    :assoc-fn (fn [m _ _] (assoc m :enable-auto-commit true))]

   [nil "--disable-auto-commit" "If set, enables automatic commits via Kafka consumers. If not provided, uses the client default."
    :assoc-fn (fn [m _ _] (assoc m :enable-auto-commit false))]

   [nil "--enable-server-auto-create-topics" "If set, enables automatic topic creation on the server. If not provided, uses the server default."
    :default  nil
    :assoc-fn (fn [m _ _] (assoc m :enable-server-auto-create-topics true))]

   [nil "--disable-server-auto-create-topics" "If set, disables automatic topic creation on the server. If not provided, uses the server default."
    :assoc-fn (fn [m _ _] (assoc m :enable-server-auto-create-topics false))]

   [nil "--final-time-limit SECONDS" "How long should we run the final generator for, at most? In seconds."
    :default  100
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "must be a positive number"]]

   [nil "--idempotence" "If true, asks producers to enable idempotence. If omitted, uses client defaults."
    :default nil]

   [nil "--max-writes-per-key LIMIT" "How many writes do we perform per key at most?"
    :default 1024
    :parse-fn parse-long
    :validate [pos? "Must be positive"]]

    [nil "--nemesis FAULTS" "A comma-separated list of nemesis faults to enable"
     :parse-fn parse-nemesis-spec
     :validate [(partial every? (into nemeses (keys special-nemeses)))
                (str "Faults must be one of " nemeses " or "
                     (cli/one-of special-nemeses))]]

    [nil "--nemesis-interval SECONDS" "How long to wait between nemesis faults."
     :default  15
     :parse-fn read-string
     :validate [#(and (number? %) (pos? %)) "must be a positive number"]]

    [nil "--partition-targets TARGETS" "A comma-separated list of nodes to target for network partitions; e.g. one,all"
     ;:default [:primaries :majorities-ring]
     :default [:one :majority :majorities-ring]
     :parse-fn parse-comma-kws
     :validate [(partial every? partition-targets) (cli/one-of partition-targets)]]

   [nil "--rate HZ" "Target number of ops/sec"
    :default  100
    :parse-fn read-string
    :validate validate-non-neg]

   [nil "--retries COUNT" "Producer retries. If omitted, uses client default."
    :parse-fn util/parse-long]

   ["-s" "--safe" "Runs with the safest settings: --disable-auto-commit, --acks all, --retries 0, --auto-offset-reset earliest, --sub-via assign. You can override individual settings by following -s with additional arguments, like so: -s --acks 0"
    :assoc-fn (fn [m _ _]
                (assoc m
                       :enable-auto-commit false
                       :acks "all"
                       :retries 0
                       :auto-offset-reset "earliest"
                       :sub-via #{:assign}))]

   [nil "--sub-via STRATEGIES" "A comma-separated list like `assign,subscribe`, which denotes how we ask clients to assign topics to themselves."
    :default #{:subscribe}
    :parse-fn (comp set parse-comma-kws)
    :validate [#(every? #{:assign :subscribe} %)
               "Can only be assign and/or subscribe"]]

   [nil "--tcpdump" "If set, grabs tcpdump traces of client->server traffic on each node."]

   ["-v" "--version STRING" "What version of Redpanda should we install? See apt list --all-versions redpanda for a full list of available versions."
    :default "21.10.1-1-e7b6714a"]

   ["-w" "--workload NAME" "Which workload should we run?"
    :parse-fn keyword
    :default  :list-append
    :validate [workloads (cli/one-of workloads)]]])

(def test-all-cli-opts
  "Additional options for test-all"
  [[nil "--versions VERSIONS" "Comma separated versions to test."
   :parse-fn #(str/split % #",\s*")
   :default  nil
   :validate [seq "Must not be empty"]]])

(defn all-tests
  "Takes parsed CLI options and constructs a sequence of tests."
  [opts]
  (let [workloads (if-let [w (:workload opts)]
                    [w]
                    (keys workloads))
        versions (or (:versions opts)
                     [(:version opts)])
        nemeses (if (nil? (:nemesis opts))
                  standard-nemeses
                  [{}])]
    (for [i (range (:test-count opts))
          v  versions
          w  workloads
          n  nemeses]
      (-> opts
          (assoc :workload w, :version v)
          (merge n)
          redpanda-test))))

(defn -main
  "CLI entry point."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn redpanda-test
                                         :opt-spec cli-opts})
                   (cli/test-all-cmd {:tests-fn all-tests
                                      :opt-spec (concat cli-opts
                                                        test-all-cli-opts)})
                   (cli/serve-cmd))
            args))
