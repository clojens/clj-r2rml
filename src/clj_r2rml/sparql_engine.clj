(ns clj-r2rml.sparql-engine
  (:use clj-r2rml.core)
  (:use clj-r2rml.triples)
  (:use clj-r2rml.sparql-parser)
  (:use clj-r2rml.sparql-update)
  (:use clojure.contrib.sql)
  (:use [clojure.contrib.json :only [read-json]]))

(defprotocol SparqlEngine
  "An interface to execute SPARQL engines against a certain backend"
  (execute [this query]))


;; Select query impl.

(defn build-abstract-query-tree
  ([pattern env]
     (condp = (:token pattern)
         "groupgraphpattern" (if (= 1 (count (:patterns pattern)))
                               (build-abstract-query-tree(first (:patterns pattern)) env)
                               (apply AND (map #(build-abstract-query-tree % env) (:patterns pattern))))
         "basicgraphpattern" (if (= 1 (count (:triplesContext pattern)))
                               (dsl-triple (let [triple(normalize-triple (first (:triplesContext pattern)) env)]
                                             triple) )
                               (apply AND (map #(dsl-triple (normalize-triple % env)) (:triplesContext pattern))))
         (if (nil? (:predicate pattern))
           (throw (Exception. (str "Token " (:token pattern) " not supported yet in the Abstract Syntax Tree")))
           (dsl-triple (normalize-triple pattern env))))))

(defn project-results
  ([projection results-map]
     (if (and (= 1 (count projection)) (= "*" (:kind (first projection))))
       results-map
       (let [vars (reverse (map (fn [var] (keyword (:value (:value var)))) projection))]
         (map (fn [result] (reduce (fn [tuple v] (assoc tuple v (infer-result-kind (get result v)))) {} vars)) results-map)))))

(defn execute-query-query
  ([parsed-query sql-context table-mappers]
     (let [env (make-ns-env parsed-query)
           pattern (:pattern (first (:units parsed-query)))
           aqt (build-abstract-query-tree pattern env)
           sql (translate (aqt table-mappers))]
       (if (nil? sql)
         (throw (Exception. "The query cannot be executed"))
         (let [result (with-context-connection sql-context
                        (with-query-results rs [sql] (vec rs)))
               projection (:projection (first (:units parsed-query)))]
           (project-results projection result))))))

;; Update query impl.


(defn update-unit-to-triples
  ([update-unit env]
     (let [quads (:quads update-unit)]
       (map (fn [token] (normalize-triple token env))
            quads))))

(defn select-quad-pattern
  ([quads table-mappers]
     (let [aqt (if (= 1 (count quads))
                 (dsl-triple (first quads))
                 (apply AND (map #(dsl-triple %) quads)))
           sql (translate (aqt table-mappers))]
       (if (nil? sql)
         (throw (Exception. "The query cannot be executed"))
         (let [result  (with-query-results rs [sql] (vec rs))
               projection [{:kind "*"}]]
           (project-results projection result))))))

(defn apply-bindings
  ([quads bindings]
     (map (fn [quad]
            (map (fn [comp]
                   (if (keyword? comp)
                     (get bindings comp)
                     comp))
                 quad))
          quads)))

(defn execute-delete-where-query
  ([unit env table-mappers]
     (let [normalized-quads (update-unit-to-triples unit env)
           results (select-quad-pattern normalized-quads table-mappers)]
       (doseq [bindings results]
         (let [bound-triples (apply-bindings normalized-quads bindings)
               sql-query (translate-delete bound-triples table-mappers false)]
           (if (coll? sql-query)
             (apply do-commands sql-query)
             (do-commands sql-query)))))))

(defn execute-update-query
  ([parsed-query sql-context table-mappers]
     (with-context-connection sql-context
       (let [env (make-ns-env parsed-query)
             rows (atom 0)]
         (doseq [unit (:units parsed-query)]
           (if (= (:kind unit) "deletewhere")
             (execute-delete-where-query unit env table-mappers)
             (let [sql-query (condp = (:kind unit)
                                 "insertdata" (translate-insert (update-unit-to-triples unit env) table-mappers)
                                 "deletedata" (translate-delete (update-unit-to-triples unit env) table-mappers false)
                                 (throw (Exception. (str "Unknown update query " (:kind unit)))))]
               (let [result (if (coll? sql-query)
                              (apply do-commands sql-query)
                              (do-commands sql-query))]
                 (when (=  result 0) (throw (Exception. "wrong update")))
                 (swap! rows #(+ % (apply + result)))))))
         @rows))))

;;
;; Engine protocol implementation
;;

(defrecord SqlSparqlEngine [sql-backend table-mappers] SparqlEngine
  (execute [this query]
           (let [parsed-query (parse-sparql query)]
             (condp = (:kind parsed-query)
                 "update" (execute-update-query parsed-query sql-backend table-mappers)
                 "query"  (execute-query-query parsed-query sql-backend table-mappers)))))


(defn make-sql-sparql-engine
  ([db-configuration table-mappers]
     (let [context (make-context db-configuration {})]
       (clj-r2rml.sparql-engine.SqlSparqlEngine. context table-mappers))))
