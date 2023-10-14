(ns json-rpc.producer)

(defprotocol IProducer
  (publish-diagnostic [this diagnostic]))
