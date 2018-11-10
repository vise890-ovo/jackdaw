# jackdaw-streams

A thin clojure wrapper around the kafka streams API.

# Usage

Some common scenarios and function signatures are described here. If you don't see
an example of the API you'd like to use, check the tests. If there isn't one there,
please raise an issue.


```clojure
(ns jackdaw-streams-demo
  (:require
    [jackdaw.streams :as k]
    [jackdaw.streams.interop :as interop]
    [jackdaw.serdes :as serde]))

(defn demo-topic [topic-name]
  {:jackdaw.topic/topic-name topic-name
   :jackdaw.serdes/key-serde (serde/serde :jackdaw.serde/edn)
   :jackdaw.serdes/value-serde (serde/serde :jackdaw.serde/edn)})
```

### kstreams/ktables

```clojure
(defn topology [builder]
  (let [table-of-foos (k/ktable builder (demo-topic "foo"))
        stream-of-bars (k/kstream builder (demo-topic "bar"))]
    (k/left-join stream-of-bars table-of-foos (fn [bar foo]
                                                (= (:id bar)
                                                   (:bar-id foo))))))
```

### transform stream values

Transform the values in a stream while keeping the keys in-tact

```clojure
(defn transformed [s]
  (k/map-values s (fn [v]
                    (transformed v))))
```

### transform keys *and* values

Sometimes you need to also update the key

```clojure
(defn transformed [s]
  (k/map s (fn [[k v]]
             [(transformed-key k)
              (transformed-value v)])))
```