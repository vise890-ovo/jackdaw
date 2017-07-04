(ns jackdaw.test.fixtures-integration-test
  (:require
   [clj-http.client :as http]
   [clj-time.core :as t]
   [clj-time.coerce :as c]
   [clojure.data.json :as json]
   [clojure.java.jdbc :as jdbc]
   [clojure.test :refer :all]
   [environ.core :as env]
   [jackdaw.client :as client]
   [jackdaw.test.config :as config]
   [jackdaw.test.fs :as fs]
   [jackdaw.test.fixtures :as fix]
   [jackdaw.test.test-config :as test-config])
  (:import
   (java.util UUID)))

(use-fixtures :once
  (join-fixtures [(fix/zookeeper test-config/zookeeper)
                  (fix/broker test-config/broker)
                  (fix/schema-registry test-config/schema-registry)]))

(def poll-timeout-ms 1000)
(def consumer-timeout-ms 5000)

(defn fuse
  "Returns a function that throws an exception when called after some time has passed."
  [millis]
  (let [end (+ millis (System/currentTimeMillis))]
    (fn []
      (if (< end (System/currentTimeMillis))
        (throw (ex-info "Timer expired" {:millis millis}))
        true))))

(deftest ^:integration integration-test
  (let [topic {:topic.metadata/name "foo"}]
    (with-open [producer (client/producer test-config/producer)
                consumer (-> (client/consumer test-config/consumer)
                             (client/subscribe topic))]

    (testing "publish!"
      (let [result (client/send! producer (client/producer-record "foo" "1" "bar"))]
        (are [key] (get (client/metadata @result) key)
          :offset
          :topic
          :toString
          :partition
          :checksum
          :serializedKeySize
          :serializedValueSize
          :timestamp)))

      (testing "consume!"
        (let [[key val] (-> (client/log-messages consumer
                                                 poll-timeout-ms
                                                 (fuse consumer-timeout-ms))
                            first)]
          (is (= ["1" "bar"] [key val])))))))

#_(deftest ^:integration kafka-connect-source-connector-test
  (let [fix (join-fixtures
              [(fix/kafka-connect test-config/kafka-connect-worker-config )
              (fn [f] (jdbc/execute! config/db-conf ["TRUNCATE kafka_connect_source_data"]) (f))])]
    (testing "kafka-connect source connector"
      (fix
        (fn []
          (let [foreign-id (UUID/randomUUID)
                date (t/now)
                repayment {:foreign_id foreign-id
                           :updated_at (c/to-sql-date date)}
                kc-query "SELECT foreign_id::varchar, id, updated_at FROM kafka_connect_source_data"
                task-config {:name "kafka-connect-source"
                             :config {:mode "incrementing"
                                      :timestamp.column.name "updated_at"
                                      :incrementing.column.name "id"
                                      :connector.class "io.confluent.connect.jdbc.JdbcSourceConnector"
                                      :connection.url (format
                                                        "jdbc:postgresql://%s:%s/%s?user=%s&password=%s"
                                                        (:db-host env/env)
                                                        (:db-port env/env)
                                                        (:db-name env/env)
                                                        (:db-user env/env)
                                                        (:db-password env/env))
                                      :name "kafka-connect-source"
                                      :query kc-query
                                      :topic.prefix "kafka-connect-source"
                                      :topics "kafka-connect-source"}}]

            (http/post (format "http://%s:%s/connectors"
                               (:kafka-connect-host env/env)
                               (:kafka-connect-port env/env))
                         {:content-type :json
                          :body (json/write-str task-config)})

            (jdbc/insert! config/db-conf
                          "kafka_connect_source_data"
                          [:foreign_id :updated_at]
                          [foreign-id (c/to-sql-date date)])

            (with-open [consumer (-> (client/consumer (assoc test-config/consumer "group.id"
                                                             (str "kafka-connect-test-" (UUID/randomUUID))))
                                     (client/subscribe {:topic.metadata/name "kafka-connect-source"}))]

                (is (= (str foreign-id) (some-> (client/timed-log-messages consumer 60000)
                                                first
                                                last
                                                (json/read-str)
                                                (get "foreign_id")))))))))))
