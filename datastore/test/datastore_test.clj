(ns datastore-test
  (:import [com.google.cloud.datastore.testing LocalDatastoreHelper]
           [com.google.cloud.datastore NullValue Entity Key])
  (:require [gclouj.datastore :refer :all]
            [clojure.test :refer :all]
            [clojure.walk :as w]))

(defrecord Person [name])

(deftest record-mapping
  (let [r (Person. "Paul")
        e (entity (incomplete-key "project-id" "Person") (w/stringify-keys r))]
    (is (= "Paul" (.getString e "name")))))

(defn datastore-helper []
  (let [datastore (doto (LocalDatastoreHelper/create 1.0)
                    (.start))]
    {:datastore  datastore
     :project-id (.projectId datastore)
     :options    (.options datastore)}))

(deftest entity-mapping
  (let [testbytes (byte-array [1 2 3 4 5])
        e (entity (incomplete-key "project-id" "Foo")
                  {"FirstName"    "Paul"
                   "Age"          35
                   "Clojurist"    true
                   "NilValue"     nil
                   "TestBytes"    testbytes
                   "Intelligence" 50.5
                   "Address"      {"City" "London"}
                   "Tags"         ["Foo" "Bar"]})]
    (is (= "Paul"            (.getString e "FirstName")))
    (is (= 35                (.getLong e "Age")))
    (is (= 50.5              (.getDouble e "Intelligence")))
    (is (true?               (.getBoolean e "Clojurist")))
    (is (instance? NullValue (.getValue e "NilValue")))
    (is (= (seq testbytes)   (seq (.toByteArray (.getBlob e "TestBytes")))))
    (is (= "London"          (-> e (.getEntity "Address") (.getString "City"))))
    (is (= ["Foo" "Bar"]     (map to-clojure (.getList e "Tags"))))))

  (deftest put-and-retrieve-entity
    (let [{:keys [datastore options project-id]} (datastore-helper)]
      (let [s    (service options)
            t    (transaction s)]
        (let [added         (add-entity t (entity (incomplete-key project-id "Foo")
                                                  {"FirstName" "Paul"}))
              added-key     (.key added)
              added2        (add-entity t (entity (incomplete-key project-id "Foo")
                                                  {"FirstName" "Peter"}))
              added-key2    (.key added2)
              added3        (add-entity t (entity (incomplete-key project-id "Foo")
                                                  {"FirstName" "Arthur"}))
              added-key3    (.key added3)]
          (.commit t)
          (let [t       (transaction s)
                fetched (get-entity t added-key)]
            (is (= "Paul" (.getString fetched "FirstName")))
            (let [new-entity (Entity/builder fetched)]
              (.set new-entity "FirstName" "Arthur"))
            (is (= 3 (count (get-entity t added-key added-key2 added-key3))))
            (is (= "Arthur" (-> (get-entity t added-key added-key2 added-key3)
                                (last)
                                (.getString "FirstName")))))))
      (.stop datastore)))

(deftest querying
  (let [{:keys [datastore options project-id]} (datastore-helper)
        s      (-> options (service))]
    ;; create some entities to query for
    (let [t (transaction s)]
      (add-entity t (entity (incomplete-key project-id "QueryFoo") {"Name" "Paul"
                                                                    "Age"  35}))
      (add-entity t (entity (incomplete-key project-id "QueryFoo") {"Name" "Pat"
                                                                    "Age"  30}))
      (.commit t))

    (let [nothing (query s {:kind "BadKind"})
          found   (query s {:kind    "QueryFoo"
                            :filters ['(:= "Name" "Paul")]})]
      (is (= 0 (count nothing)))
      (is (= 1 (count found)))
      (is (= "Paul" (.getString (first found) "Name"))))

    (let [both (query s {:kind  "QueryFoo"
                         :order [[:desc "Age"] [:asc "Name"]]})]
      (is (= "Paul"  (.getString (first both) "Name")))
      (is (= "Pat"   (.getString (last both) "Name"))))

    (is (= "Paul" (-> (query s {:kind "QueryFoo"
                                :order [[:desc "Age"]]
                                :limit 1})
                      (first)
                      (.getString "Name"))))
    (is (= "Pat" (-> (query s {:kind "QueryFoo"
                               :order [[:desc "Age"]]
                               :offset 1
                               :limit 1})
                     (first)
                     (.getString "Name"))))
    (is (instance? Key
                   (first (query s
                                 {:kind "QueryFoo"
                                  :order [[:desc "Age"]]
                                  :limit 1}
                                 :query-type :key))))
    (.stop datastore)))

(deftest add-update-delete-entity
  (let [{:keys [datastore options project-id]} (datastore-helper)
        s      (-> options (service))]

    (let [t     (transaction s)
          added (add-entity t (entity (incomplete-key project-id "QueryFoo") {"Name"     "Paul"
                                                                              "Age"      35
                                                                              "Location" {"City" "London"}}))]
      (.commit t)
      (let [t2 (transaction s)]
        (is (= {"Name" "Paul"
                "Age"  35
                "Location" {"City" "London"}} (to-clojure added)))
        (update-entity t2 (entity (.key added)
                                  (-> (to-clojure added)
                                      (assoc "Age" 36)
                                      (update-in ["Location" "City"] (constantly "Hong Kong")))))
        (.commit t2))

      (let [updated (get-entity (transaction s) (.key added))]
        (is (= 36 (.getLong updated "Age")))
        (is (= "Hong Kong" (-> updated (.getEntity "Location") (.getString "City")))))

      (let [t3 (transaction s)]
        (delete-entity t3 (.key added))
        (.commit t3)))

    (is (= 0 (count (query s {:kind "QueryFoo"}))))
    (.stop datastore)))
