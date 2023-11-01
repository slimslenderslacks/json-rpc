(ns json-rpc.server
  (:refer-clojure :exclude [run!])
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as cheshire]
   [clojure-lsp.logger :as logger]
   [clojure.core.async :as async]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.cli :refer [parse-opts]]
   [docker.json :as json]
   [docker.lsp.db :as db]
   [json-rpc.producer :as producer]
   [lsp4clj.io-server :refer [stdio-server]]
   [lsp4clj.server :as lsp.server]
   [taoensso.timbre :as timbre]
   [taoensso.timbre.appenders.core :as appenders]
   [clojure.core :as c])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn log-wrapper-fn
  [level & args]
  ;; NOTE: this does not do compile-time elision because the level isn't a constant.
  ;; We don't really care because we always log all levels.
  (timbre/log! level :p args))

(defn log! [level args fmeta]
  (timbre/log! level :p args {:?line (:line fmeta)
                              :?file (:file fmeta)
                              :?ns-str (:ns-str fmeta)}))

(defn ^:private exit-server [server]
  (logger/info "Exiting...")
  (lsp.server/shutdown server) ;; blocks, waiting up to 10s for previously received messages to be processed
  (shutdown-agents)
  (System/exit 0))

(defmethod lsp.server/receive-request "shutdown" [_ {:keys [db*]} _params]
  (logger/info "Shutting down...")
  (swap! db* assoc :running false) ;; resets db for dev
  nil)

(defmethod lsp.server/receive-notification "exit" [_ {:keys [server]} _params]
  (exit-server server))

(defmethod lsp.server/receive-notification "$/setTrace" [_ {:keys [server]} {:keys [value]}]
  (lsp.server/set-trace-level server value))

(defn run-python-app [{_req-cancelled* :lsp4clj.server/req-cancelled?
                       :keys [producer db*]}
                      {:as params}]
  (async/thread
    (let [extension-id (get params "extension/id")
          f (format "/app/%s.json" extension-id)
          python-arg (json/->str (dissoc params "extension/id"))]
      (spit f python-arg)
      (let [p (p/process {:dir "/app"}
                         "python" "app.py" (if (true? (:use-file @db*)) f python-arg))]
        (with-open [rdr (io/reader (:out p))]
          (binding [*in* rdr]
            (loop []
              (when-let [line (read-line)]
                (logger/info line)
                (when line
                  (try
                    (producer/publish-prompt producer {"extension/id" extension-id "content" (cheshire/parse-string line)})
                    (catch Throwable _t (producer/publish-prompt producer {"extension/id" extension-id "error" line})))
                  (recur))))))
        (producer/publish-exit producer (merge {"extension/id" extension-id} (select-keys @p [:exit])))))))

(defmethod lsp.server/receive-request "prompt" [_method {:keys [db* id] :as components} params]
  (logger/info (format "request id %s" id))
  (logger/info (format "params %s" params))
  (if (:running @db*)
    (if-let [extension-id (get params "extension/id")]
      (try
        (run-python-app components params)
        {:accepted {"extension/id" extension-id
                    :id id}}
        (catch Throwable t (throw (ex-info "unable to run pythong app" (ex-data t)))))
      (throw (ex-info "no extension/id in request" {})))
    (throw (ex-info "shutting down" {}))))

(defmethod lsp.server/receive-request "questions" [_method {:keys [db* id] :as _components} params]
  (logger/info (format "request id %s" id))
  (logger/info (format "params %s" params))
  (if (:running @db*)
    (if-let [extension-id (get params "extension/id")]
      (let [p (p/process
               {:dir "/app"
                :out :string}
               "python" "app.py" "questions")]
        {:content (cheshire/parse-string (:out @p))
         "extension/id" extension-id})
      (throw (ex-info "no extension/id in request" {})))
    (throw (ex-info "shutting down" {}))))

(defn ^:private monitor-server-logs [log-ch]
  ;; NOTE: if this were moved to `initialize`, after timbre has been configured,
  ;; the server's startup logs and traces would appear in the regular log file
  ;; instead of the temp log file. We don't do this though because if anything
  ;; bad happened before `initialize`, we wouldn't get any logs.
  (async/go-loop []
    (when-let [log-args (async/<! log-ch)]
      (apply log-wrapper-fn log-args)
      (recur))))

(defrecord TimbreLogger []
  logger/ILogger
  (setup [this]
    (let [log-path (str (fs/file "docker-lsp.out"))
          #_(str (java.io.File/createTempFile "docker-lsp." ".out"))]
      (timbre/merge-config! {:middleware [#(assoc % :hostname_ "")]
                             :appenders {:println {:enabled? false}
                                         :spit (appenders/spit-appender {:fname log-path})}})
      (timbre/handle-uncaught-jvm-exceptions!)
      (logger/set-logger! this)
      log-path))

  (set-log-path [_this log-path]
    (timbre/merge-config! {:appenders {:spit (appenders/spit-appender {:fname log-path})}}))

  (-info [_this fmeta arg1] (log! :info [arg1] fmeta))
  (-info [_this fmeta arg1 arg2] (log! :info [arg1 arg2] fmeta))
  (-info [_this fmeta arg1 arg2 arg3] (log! :info [arg1 arg2 arg3] fmeta))
  (-warn [_this fmeta arg1] (log! :warn [arg1] fmeta))
  (-warn [_this fmeta arg1 arg2] (log! :warn [arg1 arg2] fmeta))
  (-warn [_this fmeta arg1 arg2 arg3] (log! :warn [arg1 arg2 arg3] fmeta))
  (-error [_this fmeta arg1] (log! :error [arg1] fmeta))
  (-error [_this fmeta arg1 arg2] (log! :error [arg1 arg2] fmeta))
  (-error [_this fmeta arg1 arg2 arg3] (log! :error [arg1 arg2 arg3] fmeta))
  (-debug [_this fmeta arg1] (log! :debug [arg1] fmeta))
  (-debug [_this fmeta arg1 arg2] (log! :debug [arg1 arg2] fmeta))
  (-debug [_this fmeta arg1 arg2 arg3] (log! :debug [arg1 arg2 arg3] fmeta)))

(defrecord ^:private ClojureLspProducer
           [server db*]
  producer/IProducer

  (publish-prompt [_this p]
    (logger/info "publish-prompt " p)
    (lsp.server/discarding-stdout
     (->> p (lsp.server/send-notification server "$/prompt"))))
  (publish-exit [_this p]
    (logger/info "publish-exit " p)
    (lsp.server/discarding-stdout
     (->> p (lsp.server/send-notification server "$/exit")))))

(defn run-server! [{:keys [trace-level] :as opts}]
  (lsp.server/discarding-stdout
   (let [timbre-logger (->TimbreLogger)
         log-path (logger/setup timbre-logger)
         db (merge
             db/initial-db
             {:log-path log-path}
             (select-keys opts [:pod-exe-path :user :workspace :extension-path :use-file])
             (when-let [pat (System/getenv "DOCKER_PAT")]
               {:pat pat}))
         db* (atom db)
         log-ch (async/chan (async/sliding-buffer 20))
         server (stdio-server {:keyword-function identity
                               :in System/in
                               :out System/out
                               :log-ch log-ch
                               :trace-ch log-ch
                               :trace-level trace-level})
         producer (ClojureLspProducer. server db*)
         components {:db* db*
                     :logger timbre-logger
                     :producer producer
                     :server server
                     :current-changes-chan (async/chan 1)
                     :diagnostics-chan (async/chan 1)
                     :watched-files-chan (async/chan 1)
                     :edits-chan (async/chan 1)}]
     (logger/info "[SERVER]" "Starting server...")
     (monitor-server-logs log-ch)
     (lsp.server/start server components))))

(defn ^:private handle-action!
  [action options]
  (cond

    (= "listen" action)
    (let [finished @(run-server! options)]
      {:result-code (if (= :done finished) 0 1)})

    :else
    {:result-code 1
     :message "only listen is a supported action"}))

(def ^:private trace-levels
  #{"off" "messages" "verbose"})

(defn ^:private version []
  (->> [(str "docker desktop" "")]
       (string/join \newline)))

(defn ^:private help [options-summary]
  (->> ["Docker Project LSP"
        ""
        "All options:"
        options-summary
        ""
        "Available commands:"
        "  listen (or empty)    Start docker-lsp as server, listening to stdin."
        ""]
       (string/join \newline)))

(defn ^:private error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn s->file [s]
  (io/file
   (if (and s (string/starts-with? s "file://"))
     (java.net.URI. s)
     s)))

(defn ^:private cli-options []
  [["-h" "--help" "Print the available commands and its options"]
   [nil "--version" "Print docker-lsp version"]
   [nil "--trace-level LEVEL" "Enable trace logs between client and server, for debugging. Set to 'messages' for basic traces, or 'verbose' for more detailed traces. Defaults to 'off' for no traces."
    :default "off"
    :validate [trace-levels (str "Must be in " trace-levels)]]
   ["-s" "--settings SETTINGS" "Optional settings as edn to use for the specified command. For all available settings, check https://github.com/atomisthq/lsp/settings"
    :id :settings
    :validate [#(try (edn/read-string %) true (catch Exception _ false))
               "Invalid --settings EDN"]
    :assoc-fn #(assoc %1 %2 (edn/read-string %3))]
   [nil "--log-path PATH" "Path to use as the log path for docker-lsp.out, debug purposes only."
    :id :log-path]
   [nil "--use-file" "do not pass prompts as exec args - use file instead"
    :id :use-file]])

(comment
  (parse-opts ["--pod-exe-path" "/Users/slim"] (cli-options)))

(defn ^:private parse [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args (cli-options))]
    (cond
      (:help options)
      {:exit-message (help summary) :ok? true}

      (:version options)
      {:exit-message (version) :ok? true}

      errors
      {:exit-message (error-msg errors)}

      (or (= 0 (count arguments)) (some #{"listen"} arguments))
      {:action "listen" :options options}

      (some #{"docker-cli-plugin-metadata"} arguments)
      {:exit-message (json/->str {:SchemaVersion "0.1.0"
                                  :Vendor "Docker Inc."
                                  :Version "v0.0.1"
                                  :ShortDescription "Docker Language Server"})
       :ok? true}

      (some #{"project-facts"} arguments)
      {:action "project-facts" :options options}

      :else
      {:exit-message (help summary)})))

(defn ^:private exit [status msg]
  (when msg
    (println msg))
  (System/exit (or status 1)))

(defn run!
  "Entrypoint for lsp CLI"
  [& args]
  (let [{:keys [action options exit-message ok?]} (parse args)]
    (if exit-message
      {:result-code (if ok? 0 1)
       :message exit-message}
      (handle-action! action options))))

(defn -main
  [& args]
  (let [{:keys [result-code message]} (apply run! args)]
    (exit result-code message)))

(comment
  (System/getProperty "java.io.tmpdir")
  (-main))
