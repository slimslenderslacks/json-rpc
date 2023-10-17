(ns docker.lsp.db)

(def initial-db {:running true})
(defonce db* (atom initial-db))

