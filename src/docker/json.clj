(ns docker.json
  (:require [cheshire.core :as json]))

(defn ->obj [s]
  (json/parse-string s keyword))

(defn ->str [obj]
  (json/generate-string obj))

