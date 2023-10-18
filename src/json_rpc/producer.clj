(ns json-rpc.producer)

(defprotocol IProducer
  (publish-prompt [this params])
  (publish-exit [this params]))
