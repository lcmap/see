(ns lcmap.see.job.db
  ""
  (:require [clojure.tools.logging :as log]
            [clojure.core.match :refer [match]]
            [clojurewerkz.cassaforte.client :as cc]
            [clojurewerkz.cassaforte.cql :as cql]
            [clojurewerkz.cassaforte.query :as query]
            [lcmap.client.status-codes :as status]
            [lcmap.config.helpers :refer [init-cfg]]
            [lcmap.see.config :as see-cfg]
            [lcmap.see.util :as util]))

;;; Supporting Constants ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; XXX Use components instead? This is makes using a test configuration
;;     somewhat difficult ...  in order for this to work, we need to pass
;;     the component instead of the connection -- sadly, the connection is
;;     being used *everywhere*, so this will touch a lot of code. Created
;;     LCMAP-549 to handle this.
(def cfg ((init-cfg see-cfg/defaults) :lcmap.see))
(def job-keyspace (:job-keyspace cfg))
(def job-table (:job-table cfg))
(def results-keyspace (:results-keyspace cfg))
(def results-table (:results-table cfg))

;;; Supporting Functions ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-conn [component]
  (get-in component [:job :jobdb :conn]))

(defn make-default-row
  ""
  ([cfg id model-name]
    (make-default-row
      cfg id (:results-keyspace cfg) (:results-table cfg) model-name
      status/pending))
  ([cfg id keyspace table model-name]
    (make-default-row
      cfg id keyspace table model-name status/pending))
  ([cfg id keyspace table model-name default-status]
    {:science_model_name model-name
     :result_keyspace keyspace
     :result_table table
     :result_id id
     :status default-status}))

(defn get-results-table [conn job-id]
  (cql/use-keyspace conn job-keyspace)
  (-> conn
      (cql/select
        job-table
        (query/columns :result_table)
        (query/where [[= :job_id job-id]])
        (query/limit 1))
      (first)
      (:result_table)))

;;; API Functions ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn job? [conn job-id]
  (cql/use-keyspace conn job-keyspace)
  (cql/select-async
    conn
    job-table
    (query/where [[= :job_id job-id]])
    (query/limit 1)))

(defn result? [conn result-keyspace result-table result-id]
  (log/debugf "Checking for result of id %s in table '%s' ..."
              result-id result-table)
  (cql/use-keyspace conn result-keyspace)
  (cql/select-async
    conn
    result-table
    (query/where [[= :result_id result-id]])
    (query/limit 1)))

(defn insert-default [conn job-id default-row]
  (log/debugf "Saving %s to '%s.%s' ..." default-row job-keyspace job-table)
  (cql/use-keyspace conn job-keyspace)
  (cql/insert-async
    conn
    job-table
    (into default-row {:job_id job-id})))

(defn update-status [conn job-id new-status]
  (cql/use-keyspace conn job-keyspace)
  (cql/update-async conn
                    job-table
                    {:status new-status}
                    (query/where [[= :job_id job-id]])))

(defn save-job-result [conn result-keyspace result-table job-id job-output]
  (cql/use-keyspace conn result-keyspace)
  (cql/insert-async
    conn result-table {:result_id job-id :result job-output}))

(defn get-job-result [conn result-keyspace result-table job-id status-func]
  (cql/use-keyspace conn result-keyspace)
  (let [result (first @(result? conn result-keyspace result-table job-id))]
    (case result
      []
        (status-func conn job-id)
      nil
        (status-func conn job-id)
      (status-func result))))
